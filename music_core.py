import json
import logging
import os
import shutil
import urllib.parse
import uuid
from contextlib import contextmanager
from typing import Any, Callable

import yt_dlp

from music_config import (
    BASE_DIR,
    COOKIES_FILE,
    LOG_DIR,
    TEMP_DIR,
    USE_COOKIES,
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


logger = logging.getLogger("music_worker")
logger.setLevel(logging.INFO)
logger.propagate = False

fmt = logging.Formatter(
    fmt="%(asctime)s.%(msecs)03d %(levelname)s [%(name)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)

if not logger.handlers:
    sh = logging.StreamHandler()
    sh.setFormatter(fmt)
    logger.addHandler(sh)

    try:
        from logging.handlers import RotatingFileHandler

        fh = RotatingFileHandler(
            os.path.join(LOG_DIR, "music_worker.log"),
            maxBytes=5 * 1024 * 1024,
            backupCount=5,
            encoding="utf-8",
        )
        fh.setFormatter(fmt)
        logger.addHandler(fh)
    except Exception:
        logger.warning("RotatingFileHandler init failed, only console logging enabled")


def get_runtime_snapshot() -> dict:
    cookies_has_youtube = False
    try:
        if os.path.exists(COOKIES_FILE):
            with open(COOKIES_FILE, "r", encoding="utf-8", errors="ignore") as f:
                for line in f:
                    if "youtube.com" in line:
                        cookies_has_youtube = True
                        break
    except Exception:
        logger.warning("cookies file read failed for domain check")

    return {
        "cwd": os.getcwd(),
        "baseDir": BASE_DIR,
        "cookies": {
            "file": COOKIES_FILE,
            "exists": os.path.exists(COOKIES_FILE),
            "size": os.path.getsize(COOKIES_FILE) if os.path.exists(COOKIES_FILE) else 0,
            "hasYoutube": cookies_has_youtube,
            "enabled": USE_COOKIES,
        },
        "proxy": {
            "ytdlp": redact_proxy_url(YTDLP_PROXY),
            "ws": redact_proxy_url(WS_PROXY),
        },
        "ytDlp": {
            "version": getattr(yt_dlp, "__version__", None)
            or getattr(getattr(yt_dlp, "version", None), "__version__", "unknown"),
            "path": getattr(yt_dlp, "__file__", "unknown"),
            "jsRuntime": YTDLP_JS_RUNTIME or "none",
            "remoteComponents": YTDLP_REMOTE_COMPONENTS or "none",
            "playerClients": YTDLP_PLAYER_CLIENTS,
            "fetchPot": YTDLP_FETCH_POT,
            "potTrace": YTDLP_POT_TRACE,
            "pluginDir": summarize_path(YTDLP_PLUGIN_DIR) if os.path.isdir(YTDLP_PLUGIN_DIR) else "none",
            "potHttp": YTDLP_POT_HTTP_BASE_URL or "none",
            "potCli": summarize_path(YTDLP_POT_CLI_PATH) if os.path.isfile(YTDLP_POT_CLI_PATH) else "none",
            "potScript": summarize_path(YTDLP_POT_SCRIPT_PATH) if os.path.isfile(YTDLP_POT_SCRIPT_PATH) else "none",
        },
        "ffmpeg": shutil.which("ffmpeg"),
    }


def json_bytes_len(obj: dict) -> int:
    try:
        return len(json.dumps(obj, ensure_ascii=False).encode("utf-8"))
    except Exception:
        return -1


def redact_proxy_url(proxy_url: str | None) -> str:
    if not proxy_url:
        return "none"

    try:
        parsed = urllib.parse.urlsplit(proxy_url)
        if parsed.username is None and parsed.password is None:
            return proxy_url

        host = parsed.hostname or ""
        if parsed.username:
            host = f"{urllib.parse.quote(parsed.username, safe='')}:***@{host}"
        if parsed.port is not None:
            host = f"{host}:{parsed.port}"

        return urllib.parse.urlunsplit((parsed.scheme, host, parsed.path, parsed.query, parsed.fragment))
    except Exception:
        return "<invalid>"


def summarize_path(path: str | None) -> str:
    if not path:
        return "none"
    return os.path.abspath(path)


@contextmanager
def temporary_subprocess_proxy_env(proxy_url: str | None):
    proxy_env_keys = (
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
        "NO_PROXY",
        "no_proxy",
    )
    original_env = {key: os.environ.get(key) for key in proxy_env_keys}

    try:
        if proxy_url:
            for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                os.environ[key] = proxy_url

            no_proxy_value = original_env.get("NO_PROXY") or original_env.get("no_proxy") or ""
            no_proxy_parts = [part.strip() for part in no_proxy_value.split(",") if part.strip()]
            for local_host in ("127.0.0.1", "localhost"):
                if local_host not in no_proxy_parts:
                    no_proxy_parts.append(local_host)
            merged_no_proxy = ",".join(no_proxy_parts)
            os.environ["NO_PROXY"] = merged_no_proxy
            os.environ["no_proxy"] = merged_no_proxy

        yield
    finally:
        for key, value in original_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


class YtDlpLogger:
    def __init__(self, prefix: str):
        self.prefix = prefix

    def debug(self, msg):
        logger.debug(f"{self.prefix}{msg}")

    def info(self, msg):
        logger.info(f"{self.prefix}{msg}")

    def warning(self, msg):
        logger.warning(f"{self.prefix}{msg}")

    def error(self, msg):
        logger.error(f"{self.prefix}{msg}")


def classify_download_error_message(message: str) -> str:
    msg = (message or "").lower()
    if "too many requests" in msg or "http error 429" in msg:
        return "rate_limited"
    if "sign in to confirm you're not a bot" in msg or "sign in to confirm you’re not a bot" in msg:
        return "bot_check"
    if "the page needs to be reloaded" in msg:
        return "page_reload"
    if "only images are available for download" in msg:
        return "images_only"
    if "unable to download api page" in msg:
        return "api_page"
    if "no video formats found" in msg or "requested format is not available" in msg:
        return "no_formats"
    if "video unavailable" in msg:
        return "unavailable"
    return "unknown"


def ytdlp_search(keyword: str, limit: int = 30):
    ydl_opts = {
        "extract_flat": True,
        "skip_download": True,
        "quiet": True,
    }
    if YTDLP_PROXY:
        ydl_opts["proxy"] = YTDLP_PROXY

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(f"ytsearch{limit}:{keyword}", download=False)

    results = []
    for entry in info.get("entries", []):
        results.append(
            {
                "id": entry.get("id"),
                "title": entry.get("title"),
                "channel": entry.get("channel"),
                "duration": entry.get("duration"),
                "cover": (entry.get("thumbnails") or [{}])[-1].get("url"),
            }
        )
    return results


def ytdlp_download_mp3(
    music_id: str,
    status_callback: Callable[[dict[str, Any]], None] | None = None,
) -> str:
    file_id = str(uuid.uuid4())[:8]
    output_template = f"{TEMP_DIR}/{file_id}_%(title)s.%(ext)s"
    url = music_id if music_id.startswith("http") else f"https://www.youtube.com/watch?v={music_id}"
    pot_cli_exists = os.path.isfile(YTDLP_POT_CLI_PATH)
    pot_script_exists = os.path.isfile(YTDLP_POT_SCRIPT_PATH)

    def emit_status(stage: str, **payload: Any) -> None:
        if not status_callback:
            return
        try:
            status_callback(
                {
                    "stage": stage,
                    "musicId": music_id,
                    **payload,
                }
            )
        except Exception as e:
            logger.warning(f"status callback failed stage={stage} err={e}")

    def normalize_client(client_name: str) -> str | None:
        client = (client_name or "").strip()
        if not client or client.lower() == "default":
            return None
        return client

    def build_opts(player_client: str | None, use_cookies: bool):
        strategy_name = f"{player_client or 'default'}|cookies={'on' if use_cookies else 'off'}"
        log_prefix = f"[yt-dlp] [{music_id}] [{strategy_name}] "

        def progress_hook(progress: dict[str, Any]) -> None:
            status = progress.get("status")
            if status == "downloading":
                emit_status(
                    "downloading",
                    strategy=strategy_name,
                    filename=progress.get("filename"),
                    downloadedBytes=progress.get("downloaded_bytes") or 0,
                    totalBytes=progress.get("total_bytes") or progress.get("total_bytes_estimate"),
                    speedBps=progress.get("speed"),
                    etaSec=progress.get("eta"),
                    elapsedSec=progress.get("elapsed"),
                )
            elif status == "finished":
                emit_status(
                    "download_finished",
                    strategy=strategy_name,
                    filename=progress.get("filename"),
                    downloadedBytes=progress.get("downloaded_bytes") or 0,
                    elapsedSec=progress.get("elapsed"),
                )

        opts = {
            "format": "bestaudio/best",
            "outtmpl": output_template,
            "postprocessors": [
                {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "320"},
                {"key": "EmbedThumbnail"},
            ],
            "writethumbnail": True,
            "addmetadata": True,
            "quiet": True,
            "retries": 3,
            "fragment_retries": 3,
            "socket_timeout": 20,
            "noplaylist": True,
            "http_headers": {
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0.0.0 Safari/537.36"
                ),
                "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            },
            "allow_multiple_audio_streams": True,
            "merge_output_format": "mp4",
            "ignore_no_formats_error": True,
            "logger": YtDlpLogger(log_prefix),
        }
        if status_callback:
            opts["progress_hooks"] = [progress_hook]
        extractor_args = {}
        youtube_args = {}

        if YTDLP_JS_RUNTIME:
            opts["js_runtimes"] = {YTDLP_JS_RUNTIME: {}}
        if YTDLP_REMOTE_COMPONENTS:
            opts["remote_components"] = [YTDLP_REMOTE_COMPONENTS]
        if YTDLP_PROXY:
            opts["proxy"] = YTDLP_PROXY

        if player_client:
            youtube_args["player_client"] = [player_client]

        if use_cookies:
            opts["cookiefile"] = COOKIES_FILE

        if YTDLP_FETCH_POT in {"never", "auto", "always"}:
            youtube_args["fetch_pot"] = [YTDLP_FETCH_POT]
        if YTDLP_POT_TRACE:
            youtube_args["pot_trace"] = ["true"]

        if youtube_args:
            extractor_args["youtube"] = youtube_args
        if YTDLP_POT_HTTP_BASE_URL:
            extractor_args["youtubepot-bgutilhttp"] = {"base_url": [YTDLP_POT_HTTP_BASE_URL]}
        if pot_cli_exists:
            extractor_args["youtubepot-bgutilcli"] = {"cli_path": [YTDLP_POT_CLI_PATH]}
        if pot_script_exists:
            extractor_args["youtubepot-bgutilscript"] = {"script_path": [YTDLP_POT_SCRIPT_PATH]}
        if extractor_args:
            opts["extractor_args"] = extractor_args

        return opts

    last_err = None
    last_err_class = None
    last_strategy = None
    cookie_modes = [True, False] if USE_COOKIES else [False]
    player_clients = [normalize_client(client) for client in YTDLP_PLAYER_CLIENTS]
    if not player_clients:
        player_clients = [None, "web_safari"]

    for use_cookies in cookie_modes:
        for client in player_clients:
            strategy_name = f"{client or 'default'}|cookies={'on' if use_cookies else 'off'}"
            last_strategy = strategy_name
            emit_status(
                "attempt_start",
                strategy=strategy_name,
                useCookies=use_cookies,
                client=client or "default",
            )
            logger.info(
                f"Download attempt start music_id={music_id!r} strategy={strategy_name} "
                f"proxy={redact_proxy_url(YTDLP_PROXY)} fetch_pot={YTDLP_FETCH_POT} "
                f"pot_http={YTDLP_POT_HTTP_BASE_URL or 'none'} "
                f"pot_cli={summarize_path(YTDLP_POT_CLI_PATH) if pot_cli_exists else 'none'} "
                f"pot_script={summarize_path(YTDLP_POT_SCRIPT_PATH) if pot_script_exists else 'none'}"
            )

            try:
                with temporary_subprocess_proxy_env(YTDLP_PROXY):
                    with yt_dlp.YoutubeDL(build_opts(client, use_cookies)) as ydl:
                        info = ydl.extract_info(url, download=True)

                        if not info:
                            raise RuntimeError(f"yt-dlp returned empty info (strategy={strategy_name})")

                        final_filename = ydl.prepare_filename(info)
                        base, _ = os.path.splitext(final_filename)
                        mp3_path = base + ".mp3"

                if not os.path.exists(mp3_path):
                    prefix = f"{file_id}_"
                    candidates = [
                        os.path.join(TEMP_DIR, fn)
                        for fn in os.listdir(TEMP_DIR)
                        if fn.startswith(prefix) and fn.lower().endswith(".mp3")
                    ]
                    if candidates:
                        candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
                        mp3_path = candidates[0]

                if not os.path.exists(mp3_path):
                    raise FileNotFoundError(f"MP3 not generated ({strategy_name}): {mp3_path}")

                emit_status(
                    "completed",
                    strategy=strategy_name,
                    filePath=mp3_path,
                    fileSize=os.path.getsize(mp3_path),
                )
                return mp3_path

            except Exception as e:
                last_err = e
                last_err_class = classify_download_error_message(str(e))
                emit_status(
                    "attempt_failed",
                    strategy=strategy_name,
                    errorClass=last_err_class,
                    errorMessage=str(e),
                )
                logger.warning(
                    f"Download attempt failed strategy={strategy_name} class={last_err_class} err={e}"
                )

    raise RuntimeError(
        f"All download strategies failed last_strategy={last_strategy} class={last_err_class} err={last_err}"
    )


def log_startup_summary():
    logger.info("music_worker starting...")
    snapshot = get_runtime_snapshot()
    logger.info(
        "env"
        f" cwd={snapshot['cwd']}"
        f" base_dir={snapshot['baseDir']}"
        f" cookies_file={snapshot['cookies']['file']}"
        f" cookies_exists={snapshot['cookies']['exists']}"
        f" cookies_size={snapshot['cookies']['size']}"
        f" cookies_has_youtube={snapshot['cookies']['hasYoutube']}"
    )
    logger.info(
        "yt-dlp"
        f" version={snapshot['ytDlp']['version']}"
        f" path={snapshot['ytDlp']['path']}"
        f" cookies={snapshot['cookies']['enabled']}"
        f" js_runtime={snapshot['ytDlp']['jsRuntime']}"
        f" remote_components={snapshot['ytDlp']['remoteComponents']}"
        f" player_clients={','.join(snapshot['ytDlp']['playerClients'])}"
        f" fetch_pot={snapshot['ytDlp']['fetchPot']}"
        f" pot_trace={snapshot['ytDlp']['potTrace']}"
        f" plugin_dir={snapshot['ytDlp']['pluginDir']}"
        f" pot_http={snapshot['ytDlp']['potHttp']}"
        f" pot_cli={snapshot['ytDlp']['potCli']}"
        f" pot_script={snapshot['ytDlp']['potScript']}"
        f" proxy={snapshot['proxy']['ytdlp']}"
    )
    logger.info(f"proxy ws={snapshot['proxy']['ws']}")
    logger.info(f"ffmpeg={snapshot['ffmpeg']}")
