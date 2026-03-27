import os


BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(BASE_DIR, "logs")
TEMP_DIR = os.path.join(BASE_DIR, "temp_music")

os.makedirs(LOG_DIR, exist_ok=True)
os.makedirs(TEMP_DIR, exist_ok=True)


WS_AUTH_TOKEN = (os.environ.get("MUSIC_WS_AUTH_TOKEN") or "").strip()
IP = os.environ.get("MUSIC_LOCAL_IP", "127.0.0.1")
RIP = os.environ.get("MUSIC_REMOTE_IP", "14.103.202.40")
DEFAULT_B_WS_URL = f"ws://{RIP}:9880/usts-campus-services/ws/music"
if WS_AUTH_TOKEN:
    DEFAULT_B_WS_URL = f"{DEFAULT_B_WS_URL}?token={WS_AUTH_TOKEN}"
B_WS_URL = os.environ.get("MUSIC_B_WS_URL") or DEFAULT_B_WS_URL


CHUNK_SIZE = 256 * 1024
PROGRESS_EVERY_N_CHUNKS = 20


COOKIES_FILE = os.environ.get("MUSIC_COOKIES_FILE") or os.path.join(BASE_DIR, "cookies.txt")
USE_COOKIES = os.path.exists(COOKIES_FILE)
YTDLP_JS_RUNTIME = os.environ.get("MUSIC_YTDLP_JS_RUNTIME", "node").strip()
YTDLP_REMOTE_COMPONENTS = os.environ.get("MUSIC_YTDLP_REMOTE_COMPONENTS", "ejs:github").strip()


def get_proxy_config(env_name: str, default: str | None = None) -> str | None:
    value = (os.environ.get(env_name, default or "") or "").strip()
    if not value or value.lower() in {"off", "none", "direct"}:
        return None
    return value


def parse_csv_env(env_name: str, default_values: list[str]) -> list[str]:
    raw = os.environ.get(env_name)
    if raw is None:
        return default_values

    items = [part.strip() for part in raw.split(",")]
    items = [part for part in items if part]
    return items or default_values


def parse_int_env(env_name: str, default: int) -> int:
    raw = (os.environ.get(env_name) or "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except Exception:
        return default


def _strip_yaml_scalar(value: str) -> str:
    value = (value or "").strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def read_simple_yaml_scalar(file_path: str, key: str) -> str | None:
    try:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith("#"):
                    continue
                prefix = f"{key}:"
                if line.startswith(prefix):
                    return _strip_yaml_scalar(line[len(prefix):].strip())
    except Exception:
        return None
    return None


def normalize_controller_bind(bind_value: str) -> str:
    bind_value = (bind_value or "").strip()
    if not bind_value:
        return "127.0.0.1:10097"

    if ":" not in bind_value:
        return bind_value

    host, port = bind_value.rsplit(":", 1)
    host = host.strip().strip("[]")
    if host in {"0.0.0.0", "*", "", "::"}:
        host = "127.0.0.1"
    return f"{host}:{port}"


YTDLP_PROXY = get_proxy_config("MUSIC_YTDLP_PROXY", "http://127.0.0.1:7890")
WS_PROXY = get_proxy_config("MUSIC_WS_PROXY")
YTDLP_PLUGIN_DIR = os.environ.get(
    "MUSIC_YTDLP_PLUGIN_DIR",
    os.path.join(BASE_DIR, "yt-dlp-plugins"),
).strip()
YTDLP_POT_HTTP_BASE_URL = (os.environ.get("MUSIC_YTDLP_POT_HTTP_BASE_URL") or "").strip()
YTDLP_POT_SCRIPT_PATH = (
    os.environ.get("MUSIC_YTDLP_POT_SCRIPT_PATH")
    or os.path.join(BASE_DIR, "bin", "bgutil-pot")
).strip()
YTDLP_POT_CLI_PATH = (
    os.environ.get("MUSIC_YTDLP_POT_CLI_PATH")
    or YTDLP_POT_SCRIPT_PATH
).strip()

YTDLP_PLAYER_CLIENTS = parse_csv_env(
    "MUSIC_YTDLP_PLAYER_CLIENTS",
    ["mweb", "default", "web_safari", "tv_downgraded"],
)
YTDLP_FETCH_POT = (
    os.environ.get("MUSIC_YTDLP_FETCH_POT")
    or (
        "always"
        if YTDLP_POT_HTTP_BASE_URL
        or os.path.isfile(YTDLP_POT_CLI_PATH)
        or os.path.isfile(YTDLP_POT_SCRIPT_PATH)
        else "auto"
    )
).strip().lower()
YTDLP_POT_TRACE = (os.environ.get("MUSIC_YTDLP_POT_TRACE", "false") or "").strip().lower() == "true"


MIHOMO_CONFIG_FILE = os.environ.get("MUSIC_MIHOMO_CONFIG_FILE", "/etc/mihomo/config.yaml")
MIHOMO_CONTROLLER_BIND = normalize_controller_bind(
    os.environ.get("MUSIC_MIHOMO_CONTROLLER_BIND")
    or read_simple_yaml_scalar(MIHOMO_CONFIG_FILE, "external-controller")
    or "127.0.0.1:10097"
)
MIHOMO_CONTROLLER_URL = (
    os.environ.get("MUSIC_MIHOMO_CONTROLLER_URL")
    or f"http://{MIHOMO_CONTROLLER_BIND}"
).rstrip("/")
MIHOMO_SECRET = (
    os.environ.get("MUSIC_MIHOMO_SECRET")
    or read_simple_yaml_scalar(MIHOMO_CONFIG_FILE, "secret")
    or ""
).strip()
MIHOMO_SELECTOR_NAME = (os.environ.get("MUSIC_MIHOMO_SELECTOR_NAME") or "赔钱机场").strip()

LOCAL_API_HOST = os.environ.get("MUSIC_LOCAL_API_HOST", "127.0.0.1").strip() or "127.0.0.1"
LOCAL_API_PORT = parse_int_env("MUSIC_LOCAL_API_PORT", 18080)
LOCAL_API_MAX_WORKERS = parse_int_env("MUSIC_LOCAL_API_MAX_WORKERS", 2)
