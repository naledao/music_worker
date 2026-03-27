import asyncio
import json
import os
import struct
import time
import traceback
from typing import Any

import websockets

from music_config import (
    B_WS_URL,
    BASE_DIR,
    CHUNK_SIZE,
    COOKIES_FILE,
    IP,
    LOG_DIR,
    PROGRESS_EVERY_N_CHUNKS,
    RIP,
    TEMP_DIR,
    USE_COOKIES,
    WS_AUTH_TOKEN,
    WS_PROXY,
    YTDLP_FETCH_POT,
    YTDLP_JS_RUNTIME,
    YTDLP_PLAYER_CLIENTS,
    YTDLP_PLUGIN_DIR,
    YTDLP_POT_CLI_PATH,
    YTDLP_POT_HTTP_BASE_URL,
    YTDLP_POT_SCRIPT_PATH,
    YTDLP_POT_TRACE,
    YTDLP_PROXY,
    YTDLP_REMOTE_COMPONENTS,
)
from music_core import (
    YtDlpLogger,
    json_bytes_len,
    log_startup_summary,
    logger,
    redact_proxy_url,
    temporary_subprocess_proxy_env,
    ytdlp_download_mp3,
    ytdlp_search,
)


async def send_json(ws: Any, obj: dict):
    payload = json.dumps(obj, ensure_ascii=False)
    await ws.send(payload)


def pack_binary_chunk(request_id: str, seq: int, chunk: bytes) -> bytes:
    """
    Binary format:
    [36 bytes requestId ascii][4 bytes seq uint32 big-endian][chunk bytes]
    """
    rid = request_id.encode("ascii")
    if len(rid) != 36:
        raise ValueError("requestId must be UUID string length 36")
    return rid + struct.pack(">I", seq) + chunk


async def handle_command(ws: Any, msg: dict):
    cmd_type = msg.get("type")
    req_id = msg.get("requestId")
    payload = msg.get("payload") or {}

    logger.info(f"[IN ] cmd={cmd_type} requestId={req_id}")

    try:
        if cmd_type == "search":
            keyword = payload.get("keyword", "")
            if not keyword:
                raise ValueError("payload.keyword is empty")

            t0 = time.time()
            results = await asyncio.to_thread(ytdlp_search, keyword, 30)
            cost_ms = (time.time() - t0) * 1000

            resp = {
                "type": "search_result",
                "requestId": req_id,
                "ok": True,
                "payload": {"results": results},
            }
            logger.info(
                f"[OUT] search_result requestId={req_id} results={len(results)} "
                f"json_size={json_bytes_len(resp)}B cost={cost_ms:.1f}ms keyword={keyword!r}"
            )
            await send_json(ws, resp)

        elif cmd_type == "download":
            music_id = payload.get("music_id", "")
            if not music_id:
                raise ValueError("payload.music_id is empty")

            logger.info(f"Start download requestId={req_id} music_id={music_id!r} cookies={USE_COOKIES}")

            t0 = time.time()
            mp3_path = await asyncio.to_thread(ytdlp_download_mp3, music_id)
            dl_cost = time.time() - t0

            filename = os.path.basename(mp3_path)
            filesize = os.path.getsize(mp3_path)

            logger.info(
                f"Downloaded mp3 requestId={req_id} file={filename!r} "
                f"size={filesize}B cost={dl_cost:.2f}s path={mp3_path}"
            )

            # 1) meta (text)
            meta = {
                "type": "download_meta",
                "requestId": req_id,
                "ok": True,
                "payload": {"filename": filename, "filesize": filesize},
            }
            logger.info(f"[OUT] download_meta requestId={req_id} json_size={json_bytes_len(meta)}B")
            await send_json(ws, meta)

            # 2) binary chunks
            sent_bytes = 0
            sent_chunks = 0
            t_send0 = time.time()

            with open(mp3_path, "rb") as f:
                seq = 0
                while True:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break

                    binary_payload = pack_binary_chunk(req_id, seq, chunk)
                    await ws.send(binary_payload)

                    sent_bytes += len(chunk)
                    sent_chunks += 1

                    if sent_chunks % PROGRESS_EVERY_N_CHUNKS == 0:
                        elapsed = max(0.001, time.time() - t_send0)
                        speed = sent_bytes / elapsed
                        logger.info(
                            f"[OUT] binary_chunk requestId={req_id} seq={seq} "
                            f"sent={sent_bytes}/{filesize}B chunks={sent_chunks} "
                            f"speed={speed/1024/1024:.2f}MB/s"
                        )

                    seq += 1

            elapsed = max(0.001, time.time() - t_send0)
            logger.info(
                f"Finished sending binary requestId={req_id} sent_bytes={sent_bytes}B "
                f"chunks={sent_chunks} time={elapsed:.2f}s speed={sent_bytes/elapsed/1024/1024:.2f}MB/s"
            )

            # 3) done (text)
            done = {"type": "download_done", "requestId": req_id, "ok": True, "payload": {}}
            logger.info(f"[OUT] download_done requestId={req_id} json_size={json_bytes_len(done)}B")
            await send_json(ws, done)

        else:
            err = {
                "type": "error",
                "requestId": req_id,
                "ok": False,
                "payload": {"message": f"Unknown command type: {cmd_type}"},
            }
            logger.warning(f"[OUT] error requestId={req_id} unknown_cmd={cmd_type!r}")
            await send_json(ws, err)

    except Exception as e:
        logger.error(f"handle_command failed cmd={cmd_type} requestId={req_id} err={e}")
        logger.error(traceback.format_exc())

        # 把错误发回 B，避免 B 一直卡住
        try:
            await send_json(
                ws,
                {
                    "type": "error",
                    "requestId": req_id,
                    "ok": False,
                    "payload": {"message": str(e)},
                },
            )
        except Exception as send_err:
            logger.error(f"send error back failed requestId={req_id}: {send_err}")
            logger.error(traceback.format_exc())


async def ws_loop():
    attempt = 0
    while True:
        attempt += 1
        try:
            logger.info(
                f"Connecting to B attempt={attempt} url={B_WS_URL} "
                f"proxy={redact_proxy_url(WS_PROXY)}"
            )

            async with websockets.connect(
                B_WS_URL,
                proxy=WS_PROXY,
                ping_interval=20,
                ping_timeout=20,
                max_size=None,  # 允许接收较大 text
                close_timeout=10,
            ) as ws:
                logger.info(
                    f"Connected to B url={B_WS_URL} local={ws.local_address} remote={ws.remote_address}"
                )

                async for raw in ws:
                    if isinstance(raw, (bytes, bytearray)):
                        # 当前协议 B -> A 只发 text 命令，这里忽略 binary
                        logger.debug(f"[IN ] binary len={len(raw)} (ignored)")
                        continue

                    logger.info(f"[IN ] text len={len(raw.encode('utf-8'))}B")

                    try:
                        msg = json.loads(raw)
                    except Exception as je:
                        logger.error(f"json decode failed: {je}")
                        logger.error(f"raw text head={raw[:200]!r}")
                        continue

                    asyncio.create_task(handle_command(ws, msg))

        except websockets.exceptions.ConnectionClosedError as e:
            logger.error(
                f"WS closed (error) code={getattr(e, 'code', None)} reason={getattr(e, 'reason', None)}"
            )
            logger.error(traceback.format_exc())
        except websockets.exceptions.ConnectionClosedOK as e:
            logger.warning(
                f"WS closed (ok) code={getattr(e, 'code', None)} reason={getattr(e, 'reason', None)}"
            )
        except Exception as e:
            logger.error(f"WS disconnected: {e}")
            logger.error(traceback.format_exc())

        logger.info("Retry in 3s...")
        await asyncio.sleep(3)
if __name__ == "__main__":
    log_startup_summary()
    asyncio.run(ws_loop())
