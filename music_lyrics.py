import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from typing import Any, Callable

from music_config import (
    ANDROID_ADB_SERIAL,
    LYRICS_ADB_BIN,
    LYRICS_DEVICE_INPUT_DIR,
    LYRICS_GPU_CHUNK_MS,
    LYRICS_DEVICE_MODEL_PATH,
    LYRICS_DEVICE_OUTPUT_DIR,
    LYRICS_DEVICE_WHISPER_BIN,
    LYRICS_TIMEOUT_SEC,
    LYRICS_THREADS,
)
from music_core import logger

MIN_ADAPTIVE_GPU_CHUNK_MS = 15_000
ID3_LYRICS_FRAME_IDS = {"USLT", "SYLT"}
ID3_VERSION_2_3 = 3


def sidecar_lrc_path(audio_file_path: str) -> str:
    base_path, _ = os.path.splitext(os.path.abspath(audio_file_path))
    return f"{base_path}.lrc"


def generate_lrc_for_audio(
    music_id: str,
    audio_file_path: str,
    status_callback: Callable[[dict[str, Any]], None] | None = None,
) -> str:
    normalized_music_id = (music_id or "").strip()
    normalized_audio_path = os.path.abspath((audio_file_path or "").strip())
    if not normalized_music_id:
        raise ValueError("musicId is empty")
    if not normalized_audio_path or not os.path.exists(normalized_audio_path):
        raise FileNotFoundError(f"audio file not found: {normalized_audio_path}")
    if shutil.which(LYRICS_ADB_BIN) is None:
        raise FileNotFoundError(f"adb not found: {LYRICS_ADB_BIN}")

    local_lrc_path = sidecar_lrc_path(normalized_audio_path)
    if os.path.exists(local_lrc_path):
        embed_lrc_into_mp3(normalized_audio_path, local_lrc_path)
        emit_lyrics_status(
            status_callback,
            stage="already_exists",
            musicId=normalized_music_id,
            localLrcPath=local_lrc_path,
        )
        return local_lrc_path

    token = f"{int(time.time())}_{uuid.uuid4().hex[:8]}"
    extension = os.path.splitext(normalized_audio_path)[1].lower() or ".mp3"
    device_audio_path = f"{LYRICS_DEVICE_INPUT_DIR}/{token}{extension}"
    device_output_base = f"{LYRICS_DEVICE_OUTPUT_DIR}/{token}"
    device_lrc_path = f"{device_output_base}.lrc"
    audio_duration_ms = probe_audio_duration_ms(normalized_audio_path)

    with tempfile.TemporaryDirectory(prefix="lyrics_") as temp_dir:
        local_tmp_lrc_path = os.path.join(temp_dir, f"{token}.lrc")
        emit_lyrics_status(
            status_callback,
            stage="preparing",
            musicId=normalized_music_id,
            progress=5,
        )
        run_adb(
            [
                "shell",
                build_su_command(
                    f"mkdir -p {shell_quote(LYRICS_DEVICE_INPUT_DIR)} {shell_quote(LYRICS_DEVICE_OUTPUT_DIR)}"
                ),
            ],
            timeout=30,
        )
        emit_lyrics_status(
            status_callback,
            stage="uploading_audio",
            musicId=normalized_music_id,
            progress=15,
        )
        run_adb(
            [
                "push",
                normalized_audio_path,
                device_audio_path,
            ],
            timeout=LYRICS_TIMEOUT_SEC,
        )
        try:
            if audio_duration_ms and audio_duration_ms > LYRICS_GPU_CHUNK_MS:
                logger.info(
                    "lyrics gpu chunk mode enabled musicId=%s durationMs=%s chunkMs=%s",
                    normalized_music_id,
                    audio_duration_ms,
                    LYRICS_GPU_CHUNK_MS,
                )
                chunk_paths = transcribe_audio_in_gpu_chunks(
                    music_id=normalized_music_id,
                    device_audio_path=device_audio_path,
                    device_output_base=device_output_base,
                    temp_dir=temp_dir,
                    total_duration_ms=audio_duration_ms,
                    status_callback=status_callback,
                )
                merge_lrc_files(chunk_paths, local_tmp_lrc_path)
            else:
                emit_lyrics_status(
                    status_callback,
                    stage="transcribing",
                    musicId=normalized_music_id,
                    progress=35,
                )
                run_device_whisper_transcribe(
                    device_audio_path=device_audio_path,
                    device_output_base=device_output_base,
                    device_lrc_path=device_lrc_path,
                )
                emit_lyrics_status(
                    status_callback,
                    stage="pulling_lrc",
                    musicId=normalized_music_id,
                    progress=90,
                )
                pull_device_lrc(device_lrc_path, local_tmp_lrc_path)
        finally:
            cleanup_device_files(device_audio_path, f"{device_output_base}" + "*.lrc")

        if not os.path.exists(local_tmp_lrc_path):
            raise FileNotFoundError(f"generated lrc not found: {device_lrc_path}")

        os.replace(local_tmp_lrc_path, local_lrc_path)
        emit_lyrics_status(
            status_callback,
            stage="embedding_lyrics",
            musicId=normalized_music_id,
            progress=98,
            localLrcPath=local_lrc_path,
        )
        embed_lrc_into_mp3(normalized_audio_path, local_lrc_path)
        emit_lyrics_status(
            status_callback,
            stage="completed",
            musicId=normalized_music_id,
            progress=100,
            localLrcPath=local_lrc_path,
        )
        return local_lrc_path


def probe_audio_duration_ms(audio_file_path: str) -> int | None:
    try:
        completed = subprocess.run(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                audio_file_path,
            ],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except Exception:
        return None

    if completed.returncode != 0:
        return None

    raw_duration = (completed.stdout or "").strip()
    if not raw_duration:
        return None

    try:
        duration_ms = int(float(raw_duration) * 1000)
    except Exception:
        return None
    return duration_ms if duration_ms > 0 else None


def transcribe_audio_in_gpu_chunks(
    *,
    music_id: str,
    device_audio_path: str,
    device_output_base: str,
    temp_dir: str,
    total_duration_ms: int,
    status_callback: Callable[[dict[str, Any]], None] | None = None,
) -> list[str]:
    local_chunk_paths: list[str] = []

    for offset_ms in range(0, total_duration_ms, LYRICS_GPU_CHUNK_MS):
        chunk_duration_ms = min(LYRICS_GPU_CHUNK_MS, max(0, total_duration_ms - offset_ms))
        if chunk_duration_ms <= 0:
            break
        local_chunk_paths.extend(
            transcribe_gpu_chunk_adaptive(
                music_id=music_id,
                device_audio_path=device_audio_path,
                device_output_base=device_output_base,
                temp_dir=temp_dir,
                offset_ms=offset_ms,
                duration_ms=chunk_duration_ms,
                total_duration_ms=total_duration_ms,
                status_callback=status_callback,
            )
        )

    return local_chunk_paths


def transcribe_gpu_chunk_adaptive(
    *,
    music_id: str,
    device_audio_path: str,
    device_output_base: str,
    temp_dir: str,
    offset_ms: int,
    duration_ms: int,
    total_duration_ms: int,
    status_callback: Callable[[dict[str, Any]], None] | None = None,
) -> list[str]:
    chunk_progress = 20 + int(60 * min(offset_ms, total_duration_ms) / max(1, total_duration_ms))
    emit_lyrics_status(
        status_callback,
        stage="transcribing",
        musicId=music_id,
        progress=chunk_progress,
    )

    chunk_tag = f"{offset_ms:09d}_{duration_ms:06d}"
    chunk_base = f"{device_output_base}_{chunk_tag}"
    chunk_lrc_path = f"{chunk_base}.lrc"
    logger.info(
        "lyrics gpu chunk start musicId=%s offsetMs=%s durationMs=%s",
        music_id,
        offset_ms,
        duration_ms,
    )

    try:
        run_device_whisper_transcribe(
            device_audio_path=device_audio_path,
            device_output_base=chunk_base,
            device_lrc_path=chunk_lrc_path,
            offset_ms=offset_ms,
            duration_ms=duration_ms,
        )
    except RuntimeError as error:
        if is_gpu_device_lost_error(error) and duration_ms >= MIN_ADAPTIVE_GPU_CHUNK_MS * 2:
            first_duration_ms = max(MIN_ADAPTIVE_GPU_CHUNK_MS, duration_ms // 2)
            second_duration_ms = duration_ms - first_duration_ms
            if second_duration_ms < MIN_ADAPTIVE_GPU_CHUNK_MS:
                first_duration_ms = duration_ms - MIN_ADAPTIVE_GPU_CHUNK_MS
                second_duration_ms = MIN_ADAPTIVE_GPU_CHUNK_MS
            if 0 < second_duration_ms < duration_ms:
                logger.warning(
                    "lyrics gpu chunk split after device lost musicId=%s offsetMs=%s durationMs=%s -> %s + %s",
                    music_id,
                    offset_ms,
                    duration_ms,
                    first_duration_ms,
                    second_duration_ms,
                )
                return (
                    transcribe_gpu_chunk_adaptive(
                        music_id=music_id,
                        device_audio_path=device_audio_path,
                        device_output_base=device_output_base,
                        temp_dir=temp_dir,
                        offset_ms=offset_ms,
                        duration_ms=first_duration_ms,
                        total_duration_ms=total_duration_ms,
                        status_callback=status_callback,
                    )
                    + transcribe_gpu_chunk_adaptive(
                        music_id=music_id,
                        device_audio_path=device_audio_path,
                        device_output_base=device_output_base,
                        temp_dir=temp_dir,
                        offset_ms=offset_ms + first_duration_ms,
                        duration_ms=second_duration_ms,
                        total_duration_ms=total_duration_ms,
                        status_callback=status_callback,
                    )
                )
        raise

    local_chunk_path = os.path.join(temp_dir, f"chunk_{chunk_tag}.lrc")
    pull_progress = 80 + int(15 * min(offset_ms + duration_ms, total_duration_ms) / max(1, total_duration_ms))
    emit_lyrics_status(
        status_callback,
        stage="pulling_lrc",
        musicId=music_id,
        progress=pull_progress,
    )
    pull_device_lrc(chunk_lrc_path, local_chunk_path)
    return [local_chunk_path]


def run_device_whisper_transcribe(
    *,
    device_audio_path: str,
    device_output_base: str,
    device_lrc_path: str,
    offset_ms: int | None = None,
    duration_ms: int | None = None,
) -> None:
    command_parts = [
        f"rm -f {shell_quote(device_lrc_path)}",
        (
            f"{shell_quote(LYRICS_DEVICE_WHISPER_BIN)} "
            f"-m {shell_quote(LYRICS_DEVICE_MODEL_PATH)} "
            f"-f {shell_quote(device_audio_path)} "
            f"-l auto "
            f"-t {int(LYRICS_THREADS)} "
            f"-olrc "
            f"-of {shell_quote(device_output_base)} "
            f"-pp"
        ),
    ]
    whisper_command = command_parts.pop()
    if offset_ms is not None and offset_ms > 0:
        whisper_command += f" -ot {int(offset_ms)}"
    if duration_ms is not None and duration_ms > 0:
        whisper_command += f" -d {int(duration_ms)}"
    command_parts.append(whisper_command)
    run_adb(
        [
            "shell",
            build_su_command(" && ".join(command_parts)),
        ],
        timeout=LYRICS_TIMEOUT_SEC,
    )


def pull_device_lrc(device_lrc_path: str, local_lrc_path: str) -> None:
    run_adb(
        [
            "pull",
            device_lrc_path,
            local_lrc_path,
        ],
        timeout=60,
    )


def merge_lrc_files(chunk_paths: list[str], output_path: str) -> None:
    merged_lines: list[str] = []
    wrote_header = False

    for chunk_path in chunk_paths:
        if not os.path.exists(chunk_path):
            raise FileNotFoundError(f"generated lrc chunk not found: {chunk_path}")
        with open(chunk_path, "r", encoding="utf-8") as chunk_file:
            for raw_line in chunk_file:
                line = raw_line.strip()
                if not line:
                    continue
                if line.lower().startswith("[by:"):
                    if not wrote_header:
                        merged_lines.append(line)
                        wrote_header = True
                    continue
                merged_lines.append(line)

    if not merged_lines:
        raise FileNotFoundError("generated lrc is empty")
    if not wrote_header:
        merged_lines.insert(0, "[by:whisper.cpp]")

    with open(output_path, "w", encoding="utf-8", newline="\n") as output_file:
        output_file.write("\n".join(merged_lines) + "\n")


def is_gpu_device_lost_error(error: BaseException) -> bool:
    message = str(error)
    return "ErrorDeviceLost" in message or "DeviceLost" in message or "vk::DeviceLostError" in message


def embed_lrc_into_mp3(audio_file_path: str, lrc_file_path: str) -> None:
    normalized_audio_path = os.path.abspath((audio_file_path or "").strip())
    normalized_lrc_path = os.path.abspath((lrc_file_path or "").strip())
    if not normalized_audio_path.lower().endswith(".mp3"):
        logger.info("lyrics embedding skipped non-mp3 path=%s", normalized_audio_path)
        return
    if not os.path.exists(normalized_audio_path):
        raise FileNotFoundError(f"audio file not found: {normalized_audio_path}")
    if not os.path.exists(normalized_lrc_path):
        raise FileNotFoundError(f"lrc file not found: {normalized_lrc_path}")

    lyrics_entries = parse_lrc_entries(normalized_lrc_path)
    if not lyrics_entries:
        raise ValueError(f"lrc file has no timed lyrics: {normalized_lrc_path}")

    with open(normalized_audio_path, "rb") as audio_file:
        original_bytes = audio_file.read()

    preserved_frames, audio_payload = split_id3_tag_and_audio(original_bytes)
    plain_lyrics = "\n".join(entry["text"] for entry in lyrics_entries if entry["text"].strip()).strip()
    new_frames = preserved_frames + [
        build_uslt_frame(plain_lyrics),
        build_sylt_frame(lyrics_entries),
    ]
    tag_body = b"".join(new_frames)
    tag_header = build_id3_header(tag_body_size=len(tag_body), version=ID3_VERSION_2_3)
    updated_bytes = tag_header + tag_body + audio_payload

    with tempfile.NamedTemporaryFile(
        prefix="embed_lyrics_",
        suffix=".mp3",
        dir=os.path.dirname(normalized_audio_path) or None,
        delete=False,
    ) as temp_file:
        temp_path = temp_file.name
        temp_file.write(updated_bytes)

    os.replace(temp_path, normalized_audio_path)
    logger.info(
        "lyrics embedded into mp3 path=%s frames=%s entries=%s",
        normalized_audio_path,
        len(new_frames),
        len(lyrics_entries),
    )


def parse_lrc_entries(lrc_file_path: str) -> list[dict[str, Any]]:
    timestamp_pattern = re.compile(r"\[(\d+):(\d{2})(?:[.:](\d{1,3}))?\]")
    entries: list[dict[str, Any]] = []
    with open(lrc_file_path, "r", encoding="utf-8") as lrc_file:
        for raw_line in lrc_file:
            line = raw_line.strip()
            if not line:
                continue
            matches = list(timestamp_pattern.finditer(line))
            if not matches:
                continue
            text = timestamp_pattern.sub("", line).strip()
            if not text:
                continue
            for match in matches:
                minutes = int(match.group(1))
                seconds = int(match.group(2))
                fraction = match.group(3) or "0"
                if len(fraction) == 2:
                    milliseconds = int(fraction) * 10
                elif len(fraction) == 1:
                    milliseconds = int(fraction) * 100
                else:
                    milliseconds = int(fraction[:3].ljust(3, "0"))
                timestamp_ms = (minutes * 60 + seconds) * 1000 + milliseconds
                entries.append(
                    {
                        "timestampMs": timestamp_ms,
                        "text": text,
                    }
                )
    entries.sort(key=lambda item: (int(item["timestampMs"]), str(item["text"])))
    return entries


def split_id3_tag_and_audio(file_bytes: bytes) -> tuple[list[bytes], bytes]:
    if len(file_bytes) < 10 or file_bytes[:3] != b"ID3":
        return [], file_bytes

    version = file_bytes[3]
    flags = file_bytes[5]
    tag_body_size = decode_synchsafe_int(file_bytes[6:10])
    tag_total_size = 10 + tag_body_size
    if version == 4 and (flags & 0x10):
        tag_total_size += 10

    tag_body = file_bytes[10:10 + tag_body_size]
    audio_payload = file_bytes[tag_total_size:]
    if version not in (3, 4):
        return [], file_bytes[tag_total_size:]

    preserved_frames: list[bytes] = []
    cursor = 0

    if flags & 0x40:
        if version == 3 and len(tag_body) >= 4:
            ext_size = int.from_bytes(tag_body[:4], "big")
            cursor = min(len(tag_body), 4 + ext_size)
        elif version == 4 and len(tag_body) >= 4:
            ext_size = decode_synchsafe_int(tag_body[:4])
            cursor = min(len(tag_body), ext_size)

    while cursor + 10 <= len(tag_body):
        frame_header = tag_body[cursor:cursor + 10]
        frame_id = frame_header[:4]
        if frame_id == b"\x00\x00\x00\x00":
            break
        if not all(48 <= byte <= 57 or 65 <= byte <= 90 for byte in frame_id):
            break

        if version == 4:
            frame_size = decode_synchsafe_int(frame_header[4:8])
        else:
            frame_size = int.from_bytes(frame_header[4:8], "big")
        if frame_size <= 0 or cursor + 10 + frame_size > len(tag_body):
            break

        raw_frame = tag_body[cursor:cursor + 10 + frame_size]
        frame_name = frame_id.decode("ascii", errors="ignore")
        if frame_name not in ID3_LYRICS_FRAME_IDS:
            preserved_frames.append(raw_frame)
        cursor += 10 + frame_size

    return preserved_frames, audio_payload


def build_id3_header(tag_body_size: int, version: int = ID3_VERSION_2_3) -> bytes:
    return b"ID3" + bytes([version, 0, 0]) + encode_synchsafe_int(tag_body_size)


def build_uslt_frame(plain_lyrics: str) -> bytes:
    text = plain_lyrics.strip() or " "
    payload = (
        b"\x01"
        + b"und"
        + encode_utf16_id3("")
        + b"\x00\x00"
        + encode_utf16_id3(text)
    )
    return build_id3_frame("USLT", payload, version=ID3_VERSION_2_3)


def build_sylt_frame(entries: list[dict[str, Any]]) -> bytes:
    payload = bytearray()
    payload.extend(b"\x01")
    payload.extend(b"und")
    payload.extend(b"\x02")
    payload.extend(b"\x01")
    payload.extend(encode_utf16_id3(""))
    payload.extend(b"\x00\x00")
    for entry in entries:
        payload.extend(encode_utf16_id3(str(entry["text"])))
        payload.extend(b"\x00\x00")
        payload.extend(int(entry["timestampMs"]).to_bytes(4, "big", signed=False))
    return build_id3_frame("SYLT", bytes(payload), version=ID3_VERSION_2_3)


def build_id3_frame(frame_id: str, payload: bytes, version: int = ID3_VERSION_2_3) -> bytes:
    if version == 4:
        size_bytes = encode_synchsafe_int(len(payload))
    else:
        size_bytes = len(payload).to_bytes(4, "big")
    return frame_id.encode("ascii") + size_bytes + b"\x00\x00" + payload


def encode_synchsafe_int(value: int) -> bytes:
    safe_value = max(0, int(value))
    return bytes(
        [
            (safe_value >> 21) & 0x7F,
            (safe_value >> 14) & 0x7F,
            (safe_value >> 7) & 0x7F,
            safe_value & 0x7F,
        ]
    )


def decode_synchsafe_int(value: bytes) -> int:
    if len(value) != 4:
        return 0
    return (
        ((value[0] & 0x7F) << 21)
        | ((value[1] & 0x7F) << 14)
        | ((value[2] & 0x7F) << 7)
        | (value[3] & 0x7F)
    )


def encode_utf16_id3(value: str) -> bytes:
    return (value or "").encode("utf-16")


def emit_lyrics_status(
    status_callback: Callable[[dict[str, Any]], None] | None,
    *,
    stage: str,
    musicId: str,
    progress: int | None = None,
    localLrcPath: str | None = None,
) -> None:
    if status_callback is None:
        return
    payload: dict[str, Any] = {
        "stage": stage,
        "musicId": musicId,
    }
    if progress is not None:
        payload["progress"] = int(progress)
    if localLrcPath is not None:
        payload["localLrcPath"] = localLrcPath
    try:
        status_callback(payload)
    except Exception as error:
        logger.warning("lyrics status callback failed stage=%s err=%s", stage, error)


def run_adb(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    ensure_adb_connected()
    command = [LYRICS_ADB_BIN]
    if ANDROID_ADB_SERIAL:
        command.extend(["-s", ANDROID_ADB_SERIAL])
    command.extend(args)
    logger.info("lyrics adb command: %s", " ".join(shell_quote(part) for part in command))
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if completed.returncode != 0:
        stdout = (completed.stdout or "").strip()
        stderr = (completed.stderr or "").strip()
        raise RuntimeError(
            f"adb command failed code={completed.returncode} stdout={stdout or '<empty>'} stderr={stderr or '<empty>'}"
        )
    return completed


def ensure_adb_connected() -> None:
    if not ANDROID_ADB_SERIAL or ":" not in ANDROID_ADB_SERIAL:
        return

    completed = subprocess.run(
        [LYRICS_ADB_BIN, "connect", ANDROID_ADB_SERIAL],
        capture_output=True,
        text=True,
        timeout=15,
        check=False,
    )
    if completed.returncode != 0:
        stdout = (completed.stdout or "").strip()
        stderr = (completed.stderr or "").strip()
        raise RuntimeError(
            f"adb connect failed code={completed.returncode} stdout={stdout or '<empty>'} stderr={stderr or '<empty>'}"
        )


def cleanup_device_files(device_audio_path: str, device_lrc_path: str) -> None:
    try:
        cleanup_targets = []
        for path in (device_audio_path, device_lrc_path):
            if any(token in path for token in ("*", "?", "[")):
                cleanup_targets.append(path)
            else:
                cleanup_targets.append(shell_quote(path))
        run_adb(
            [
                "shell",
                build_su_command(
                    f"rm -f {' '.join(cleanup_targets)}"
                ),
            ],
            timeout=30,
        )
    except Exception as error:
        logger.warning("lyrics cleanup failed audio=%s lrc=%s err=%s", device_audio_path, device_lrc_path, error)


def build_su_command(inner_command: str) -> str:
    return f"su -c {shell_quote(inner_command)}"


def shell_quote(value: str) -> str:
    escaped = (value or "").replace("'", "'\"'\"'")
    return f"'{escaped}'"
