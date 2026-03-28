import glob
import json
import os
import hashlib
import re
import threading
import time
import traceback
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from music_config import (
    LOCAL_API_HOST,
    LOCAL_API_MAX_WORKERS,
    LOCAL_API_PORT,
    MIHOMO_CONTROLLER_URL,
    MIHOMO_SECRET,
    MIHOMO_SELECTOR_NAME,
    DOWNLOAD_INDEX_DB,
    YTDLP_PROXY,
)
from music_download_store import DownloadedMusicStore
from music_core import get_runtime_snapshot, log_startup_summary, logger, ytdlp_download_mp3, ytdlp_search


def now_ts() -> float:
    return time.time()


def iso_ts(ts: float | None = None) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts or now_ts()))


def build_content_disposition(download_name: str) -> str:
    file_name = os.path.basename((download_name or "").strip()) or "download"
    file_name = file_name.replace("\\", "_").replace("/", "_")

    ascii_name = file_name.encode("ascii", "ignore").decode("ascii")
    ascii_name = ascii_name.replace('"', "_").replace(";", "_").strip()
    if not ascii_name:
        _, extension = os.path.splitext(file_name)
        ascii_extension = extension.encode("ascii", "ignore").decode("ascii")
        ascii_name = f"download{ascii_extension}"

    encoded_name = urllib.parse.quote(file_name, safe="")
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{encoded_name}"


def repo_path(*parts: str) -> str:
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), *parts)


ALLOWED_COVER_HOST_SUFFIXES = (
    "ytimg.com",
    "ggpht.com",
    "googleusercontent.com",
)


def is_allowed_cover_url(url: str) -> bool:
    try:
        parsed = urllib.parse.urlsplit((url or "").strip())
    except Exception:
        return False

    if parsed.scheme not in ("http", "https"):
        return False

    host = (parsed.hostname or "").strip().lower()
    if not host:
        return False

    return any(host == suffix or host.endswith(f".{suffix}") for suffix in ALLOWED_COVER_HOST_SUFFIXES)


def fetch_cover_via_proxy(url: str) -> tuple[bytes, str]:
    if not is_allowed_cover_url(url):
        raise ValueError("unsupported cover url")

    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        },
    )

    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler(
            {
                "http": YTDLP_PROXY,
                "https": YTDLP_PROXY,
            }
        )
    ) if YTDLP_PROXY else urllib.request.build_opener()

    with opener.open(request, timeout=15) as resp:
        body = resp.read()
        content_type = (resp.headers.get("Content-Type") or "application/octet-stream").split(";", 1)[0].strip()
    if not body:
        raise FileNotFoundError("empty cover response")
    return body, content_type


def mihomo_request(method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    url = f"{MIHOMO_CONTROLLER_URL}{path}"
    headers = {}
    if MIHOMO_SECRET:
        headers["Authorization"] = f"Bearer {MIHOMO_SECRET}"

    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, method=method.upper(), headers=headers)
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read()
        if not body:
            return None
        return json.loads(body.decode("utf-8"))


def get_current_proxy() -> dict[str, Any]:
    selector = mihomo_request(
        "GET",
        f"/proxies/{urllib.parse.quote(MIHOMO_SELECTOR_NAME, safe='')}",
    )
    return {
        "selector": MIHOMO_SELECTOR_NAME,
        "name": selector.get("now"),
        "alive": selector.get("alive"),
        "options": selector.get("all", []),
    }


def select_proxy(name: str) -> dict[str, Any]:
    mihomo_request(
        "PUT",
        f"/proxies/{urllib.parse.quote(MIHOMO_SELECTOR_NAME, safe='')}",
        {"name": name},
    )
    return get_current_proxy()


def get_android_apk_path(build_type: str) -> str:
    return repo_path("android-app", "app", "build", "outputs", "apk", build_type, f"app-{build_type}.apk")


def get_android_apk_metadata_path(build_type: str) -> str:
    return repo_path("android-app", "app", "build", "outputs", "apk", build_type, "output-metadata.json")


def get_preferred_android_apk_artifacts() -> tuple[str, str]:
    release_apk_path = get_android_apk_path("release")
    if os.path.exists(release_apk_path):
        return release_apk_path, get_android_apk_metadata_path("release")
    return get_android_apk_path("debug"), get_android_apk_metadata_path("debug")


def get_desktop_distribution_settings() -> tuple[str, str]:
    package_name = "YinZhaoDesktop"
    package_version = "0.1.1"
    build_gradle_path = repo_path("desktop-app", "build.gradle.kts")
    if not os.path.exists(build_gradle_path):
        return package_name, package_version

    with open(build_gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    package_name_match = re.search(r'val\s+desktopPackageName\s*=\s*"([^"]+)"', content)
    package_version_match = re.search(r'val\s+desktopAppVersion\s*=\s*"([^"]+)"', content)
    if package_name_match:
        package_name = package_name_match.group(1).strip() or package_name
    if package_version_match:
        package_version = package_version_match.group(1).strip() or package_version
    return package_name, package_version


def guess_update_content_type(file_path: str) -> str:
    extension = os.path.splitext(file_path)[1].lower()
    if extension == ".msi":
        return "application/x-msi"
    if extension == ".exe":
        return "application/vnd.microsoft.portable-executable"
    if extension == ".apk":
        return "application/vnd.android.package-archive"
    return "application/octet-stream"


def get_desktop_managed_packages_dir() -> str:
    return repo_path("run", "app-packages", "desktop")


def get_desktop_managed_manifest_path() -> str:
    return os.path.join(get_desktop_managed_packages_dir(), "manifest.json")


def normalize_desktop_package_kind(kind_raw: str | None) -> str:
    kind = (kind_raw or "").strip().lower()
    if kind in ("", "auto", "any", "default"):
        return ""
    if kind in ("exe", "msi"):
        return kind
    raise ValueError(f"unsupported desktop package kind: {kind_raw}")


def get_desktop_package_kind_order(kind: str = "") -> list[str]:
    if kind:
        return [kind]
    # The Windows desktop app defaults to EXE for update/install flow.
    return ["exe", "msi"]


def infer_desktop_version_name(file_name: str, package_name: str, fallback: str | None = None) -> str | None:
    base_name = os.path.basename(file_name)
    package_pattern = rf"^{re.escape(package_name)}-(.+)\.(?:exe|msi)$"
    match = re.match(package_pattern, base_name, flags=re.IGNORECASE)
    if match:
        version_name = match.group(1).strip()
        if version_name:
            return version_name

    generic_match = re.search(r"-(\d+(?:\.\d+)+(?:[-+._A-Za-z0-9]*)?)\.(?:exe|msi)$", base_name, flags=re.IGNORECASE)
    if generic_match:
        version_name = generic_match.group(1).strip()
        if version_name:
            return version_name
    return fallback


def load_desktop_managed_manifest() -> dict[str, Any] | None:
    manifest_path = get_desktop_managed_manifest_path()
    if not os.path.exists(manifest_path):
        return None
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    if isinstance(manifest, dict):
        return manifest
    return None


def build_desktop_artifact_record(
    file_path: str,
    package_kind: str,
    source: str,
    package_name: str,
    fallback_version: str,
    package_info: dict[str, Any] | None = None,
    manifest: dict[str, Any] | None = None,
) -> dict[str, Any]:
    package_info = package_info if isinstance(package_info, dict) else {}
    manifest = manifest if isinstance(manifest, dict) else {}
    version_name = (
        package_info.get("versionName")
        or manifest.get("versionName")
        or infer_desktop_version_name(os.path.basename(file_path), package_name, fallback_version)
        or fallback_version
    )
    return {
        "kind": package_kind,
        "source": source,
        "versionName": version_name,
    }


def get_managed_desktop_installer_artifact(kind: str = "") -> tuple[str, str, dict[str, Any]] | None:
    managed_dir = get_desktop_managed_packages_dir()
    if not os.path.isdir(managed_dir):
        return None

    package_name, package_version = get_desktop_distribution_settings()
    manifest = load_desktop_managed_manifest()
    packages = manifest.get("packages") if isinstance(manifest, dict) else {}
    if not isinstance(packages, dict):
        packages = {}

    for package_kind in get_desktop_package_kind_order(kind):
        package_info = packages.get(package_kind)
        if isinstance(package_info, dict):
            file_name = os.path.basename(str(package_info.get("fileName") or ""))
            if file_name:
                candidate = os.path.join(managed_dir, file_name)
                if os.path.exists(candidate):
                    return (
                        candidate,
                        guess_update_content_type(candidate),
                        build_desktop_artifact_record(
                            candidate,
                            package_kind,
                            "managed",
                            package_name,
                            package_version,
                            package_info=package_info,
                            manifest=manifest,
                        ),
                    )

        matches = [path for path in glob.glob(os.path.join(managed_dir, f"*.{package_kind}")) if os.path.isfile(path)]
        if matches:
            candidate = max(matches, key=os.path.getmtime)
            return (
                candidate,
                guess_update_content_type(candidate),
                build_desktop_artifact_record(
                    candidate,
                    package_kind,
                    "managed",
                    package_name,
                    package_version,
                    manifest=manifest,
                ),
            )
    return None


def get_build_desktop_installer_artifact(kind: str = "") -> tuple[str, str, dict[str, Any]]:
    package_name, package_version = get_desktop_distribution_settings()
    base_dir = repo_path("desktop-app", "build", "compose", "binaries", "main")
    preferred_candidates = [
        os.path.join(base_dir, package_kind, f"{package_name}-{package_version}.{package_kind}")
        for package_kind in get_desktop_package_kind_order(kind)
    ]
    for candidate in preferred_candidates:
        if os.path.exists(candidate):
            package_kind = os.path.splitext(candidate)[1].lstrip(".").lower()
            return (
                candidate,
                guess_update_content_type(candidate),
                build_desktop_artifact_record(
                    candidate,
                    package_kind,
                    "build-output",
                    package_name,
                    package_version,
                ),
            )

    fallback_patterns = [
        os.path.join(base_dir, package_kind, f"*.{package_kind}")
        for package_kind in get_desktop_package_kind_order(kind)
    ]
    for pattern in fallback_patterns:
        matches = sorted(glob.glob(pattern), key=os.path.getmtime, reverse=True)
        if matches:
            candidate = matches[0]
            package_kind = os.path.splitext(candidate)[1].lstrip(".").lower()
            return (
                candidate,
                guess_update_content_type(candidate),
                build_desktop_artifact_record(
                    candidate,
                    package_kind,
                    "build-output",
                    package_name,
                    package_version,
                ),
            )

    raise FileNotFoundError(f"desktop installer not found under: {base_dir}")


def get_preferred_desktop_installer_artifact(kind: str = "") -> tuple[str, str, dict[str, Any]]:
    managed_artifact = get_managed_desktop_installer_artifact(kind)
    if managed_artifact is not None:
        return managed_artifact
    return get_build_desktop_installer_artifact(kind)


def sha256_file(file_path: str) -> str:
    digest = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def get_android_app_update_payload() -> dict[str, Any]:
    apk_path, metadata_path = get_preferred_android_apk_artifacts()
    if not os.path.exists(apk_path):
        raise FileNotFoundError(f"apk not found: {apk_path}")

    version_code = None
    version_name = None
    file_name = os.path.basename(apk_path)

    if os.path.exists(metadata_path):
        with open(metadata_path, "r", encoding="utf-8") as f:
            metadata = json.load(f)
        element = ((metadata.get("elements") or [{}])[:1] or [{}])[0]
        version_code = element.get("versionCode")
        version_name = element.get("versionName")
        file_name = element.get("outputFile") or file_name

    return {
        "versionCode": version_code,
        "versionName": version_name,
        "fileName": file_name,
        "fileSize": os.path.getsize(apk_path),
        "sha256": sha256_file(apk_path),
        "updatedAt": iso_ts(os.path.getmtime(apk_path)),
        "downloadPath": "/api/app/apk",
    }


def get_desktop_app_update_payload(kind: str = "") -> dict[str, Any]:
    installer_path, _, artifact = get_preferred_desktop_installer_artifact(kind)
    package_name, package_version = get_desktop_distribution_settings()
    file_name = os.path.basename(installer_path)
    package_kind = str(artifact.get("kind") or os.path.splitext(file_name)[1].lstrip(".").lower() or "exe")
    download_path = "/api/app/package?platform=desktop"
    if package_kind:
        download_path = f"{download_path}&kind={urllib.parse.quote(package_kind, safe='')}"
    return {
        "versionCode": None,
        "versionName": artifact.get("versionName") or infer_desktop_version_name(file_name, package_name, package_version),
        "fileName": file_name,
        "fileSize": os.path.getsize(installer_path),
        "sha256": sha256_file(installer_path),
        "updatedAt": iso_ts(os.path.getmtime(installer_path)),
        "downloadPath": download_path,
        "packageKind": package_kind,
        "managedByBackend": artifact.get("source") == "managed",
        "source": artifact.get("source"),
    }


def normalize_update_platform(platform_raw: str | None) -> str:
    platform = (platform_raw or "android").strip().lower()
    if platform in ("", "android"):
        return "android"
    if platform in ("desktop", "windows", "win"):
        return "desktop"
    raise ValueError(f"unsupported update platform: {platform_raw}")


def get_app_update_payload(platform: str = "android", kind: str = "") -> dict[str, Any]:
    if platform == "desktop":
        return get_desktop_app_update_payload(kind)
    return get_android_app_update_payload()


def get_app_update_artifact(platform: str = "android", kind: str = "") -> tuple[str, str]:
    if platform == "desktop":
        artifact_path, content_type, _ = get_preferred_desktop_installer_artifact(kind)
        return artifact_path, content_type
    apk_path, _ = get_preferred_android_apk_artifacts()
    return apk_path, guess_update_content_type(apk_path)


class TaskManager:
    def __init__(self, max_workers: int):
        self._tasks: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._active_download_tasks_by_music_id: dict[str, str] = {}
        self._executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="music-local-api")

    @staticmethod
    def _is_task_active(task: dict[str, Any] | None) -> bool:
        if not task:
            return False
        return task.get("status") in {"queued", "running"}

    def _pop_active_download_task_locked(self, music_id: str, task_id: str | None = None) -> None:
        active_task_id = self._active_download_tasks_by_music_id.get(music_id)
        if active_task_id is None:
            return
        if task_id is None or active_task_id == task_id:
            self._active_download_tasks_by_music_id.pop(music_id, None)

    def _get_reusable_task_locked(self, music_id: str) -> dict[str, Any] | None:
        active_task_id = self._active_download_tasks_by_music_id.get(music_id)
        if not active_task_id:
            return None

        task = self._tasks.get(active_task_id)
        if not task:
            self._active_download_tasks_by_music_id.pop(music_id, None)
            return None

        if self._is_task_active(task):
            return self._copy_task(task)

        self._active_download_tasks_by_music_id.pop(music_id, None)
        if task.get("status") == "finished":
            file_path = os.path.abspath((task.get("filePath") or "").strip())
            if file_path and os.path.exists(file_path):
                return self._copy_task(task)
        return None

    def _touch(self, task: dict[str, Any]) -> None:
        task["updatedAt"] = iso_ts()

    def _copy_task(self, task: dict[str, Any]) -> dict[str, Any]:
        return json.loads(json.dumps(task, ensure_ascii=False))

    def create_download_task(self, music_id: str) -> dict[str, Any]:
        music_id = (music_id or "").strip()
        if not music_id:
            raise ValueError("musicId is empty")

        with self._lock:
            reusable_task = self._get_reusable_task_locked(music_id)
            if reusable_task is not None:
                logger.info(
                    f"local api reuse active download taskId={reusable_task.get('taskId')} musicId={music_id} "
                    f"status={reusable_task.get('status')}"
                )
                return reusable_task

            existing_download = DOWNLOADED_MUSIC_STORE.get_download(music_id)
            task_id = str(uuid.uuid4())
            task = {
                "taskId": task_id,
                "type": "download",
                "musicId": music_id,
                "status": "queued",
                "stage": "queued",
                "progress": 0,
                "createdAt": iso_ts(),
                "updatedAt": iso_ts(),
                "filename": None,
                "filePath": None,
                "fileSize": None,
                "downloadedBytes": 0,
                "totalBytes": None,
                "speedBps": None,
                "etaSec": None,
                "strategy": None,
                "errorMessage": None,
                "errorClass": None,
            }
            self._tasks[task_id] = task

            if existing_download:
                task.update(
                    status="finished",
                    stage="finished",
                    progress=100,
                    filename=existing_download.get("filename"),
                    filePath=existing_download.get("filePath"),
                    fileSize=existing_download.get("fileSize"),
                    strategy="sqlite-cache",
                )
                self._touch(task)
                logger.info(
                    f"local api reuse existing download taskId={task_id} musicId={music_id} "
                    f"path={existing_download.get('filePath')}"
                )
                return self._copy_task(task)

            self._active_download_tasks_by_music_id[music_id] = task_id

        try:
            self._executor.submit(self._run_download, task_id, music_id)
        except Exception:
            with self._lock:
                self._tasks.pop(task_id, None)
                self._pop_active_download_task_locked(music_id, task_id)
            raise
        return self.get_task(task_id) or task

    def update_task(self, task_id: str, **fields: Any) -> None:
        with self._lock:
            task = self._tasks.get(task_id)
            if not task:
                return
            task.update(fields)
            self._touch(task)
            if not self._is_task_active(task):
                self._pop_active_download_task_locked(str(task.get("musicId") or "").strip(), task_id)

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            return self._copy_task(task) if task else None

    def list_tasks(self) -> list[dict[str, Any]]:
        with self._lock:
            tasks = [self._copy_task(task) for task in self._tasks.values()]
        tasks.sort(key=lambda item: item["createdAt"], reverse=True)
        return tasks

    def stats(self) -> dict[str, int]:
        with self._lock:
            tasks = list(self._tasks.values())
        return {
            "total": len(tasks),
            "queued": sum(1 for task in tasks if task["status"] == "queued"),
            "running": sum(1 for task in tasks if task["status"] == "running"),
            "finished": sum(1 for task in tasks if task["status"] == "finished"),
            "failed": sum(1 for task in tasks if task["status"] == "failed"),
        }

    def _run_download(self, task_id: str, music_id: str) -> None:
        self.update_task(task_id, status="running", stage="starting", progress=1)

        def on_status(event: dict[str, Any]) -> None:
            stage = event.get("stage")
            strategy = event.get("strategy")
            base_fields = {
                "stage": stage,
                "strategy": strategy,
            }
            if stage == "attempt_start":
                self.update_task(
                    task_id,
                    **base_fields,
                    progress=max(5, self.get_task(task_id)["progress"]),
                    errorMessage=None,
                    errorClass=None,
                )
                return

            if stage == "downloading":
                downloaded = event.get("downloadedBytes") or 0
                total = event.get("totalBytes")
                if total:
                    progress = min(95, max(10, int(downloaded * 90 / total)))
                else:
                    progress = max(10, self.get_task(task_id)["progress"])
                self.update_task(
                    task_id,
                    **base_fields,
                    progress=progress,
                    downloadedBytes=downloaded,
                    totalBytes=total,
                    speedBps=event.get("speedBps"),
                    etaSec=event.get("etaSec"),
                    errorMessage=None,
                    errorClass=None,
                )
                return

            if stage == "download_finished":
                self.update_task(
                    task_id,
                    **base_fields,
                    progress=max(95, self.get_task(task_id)["progress"]),
                    downloadedBytes=event.get("downloadedBytes") or 0,
                    errorMessage=None,
                    errorClass=None,
                )
                return

            if stage == "attempt_failed":
                self.update_task(
                    task_id,
                    **base_fields,
                    errorMessage=event.get("errorMessage"),
                    errorClass=event.get("errorClass"),
                )
                return

        try:
            path = ytdlp_download_mp3(music_id, status_callback=on_status)
            file_size = os.path.getsize(path)
            recorded_download = DOWNLOADED_MUSIC_STORE.record_download(
                music_id=music_id,
                file_path=path,
                downloaded_at=iso_ts(),
            ) or {}
            self.update_task(
                task_id,
                status="finished",
                stage="finished",
                progress=100,
                filename=recorded_download.get("filename") or os.path.basename(path),
                filePath=recorded_download.get("filePath") or path,
                fileSize=recorded_download.get("fileSize") or file_size,
                errorMessage=None,
                errorClass=None,
            )
        except Exception as e:
            logger.error(f"local api download failed taskId={task_id} musicId={music_id} err={e}")
            logger.error(traceback.format_exc())
            self.update_task(
                task_id,
                status="failed",
                stage="failed",
                progress=100,
                errorMessage=str(e),
            )


DOWNLOADED_MUSIC_STORE = DownloadedMusicStore(DOWNLOAD_INDEX_DB)
TASK_MANAGER = TaskManager(max_workers=LOCAL_API_MAX_WORKERS)


class MusicLocalApiHandler(BaseHTTPRequestHandler):
    server_version = "MusicLocalApi/0.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        logger.info(f"[local_api] {self.client_address[0]} {fmt % args}")

    def _send_json(self, status_code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, status_code: int, body: bytes, content_type: str, cache_control: str | None = None) -> None:
        self.send_response(status_code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if cache_control:
            self.send_header("Cache-Control", cache_control)
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, file_path: str, download_name: str, content_type: str = "application/octet-stream") -> None:
        file_size = os.path.getsize(file_path)
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(file_size))
        self.send_header("Content-Disposition", build_content_disposition(download_name))
        self.end_headers()
        with open(file_path, "rb") as f:
            while True:
                chunk = f.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def _ok(self, payload: dict[str, Any]) -> None:
        self._send_json(200, {"ok": True, "payload": payload})

    def _error(self, status_code: int, message: str) -> None:
        self._send_json(status_code, {"ok": False, "payload": {"message": message}})

    def _request_scheme(self) -> str:
        forwarded = (self.headers.get("Forwarded") or "").strip()
        if forwarded:
            for item in forwarded.split(";"):
                key, _, value = item.partition("=")
                if key.strip().lower() == "proto":
                    proto = value.strip().strip('"').lower()
                    if proto:
                        return proto

        forwarded_proto = (self.headers.get("X-Forwarded-Proto") or "").split(",", 1)[0].strip().lower()
        if forwarded_proto:
            return forwarded_proto
        return "http"

    def _request_host(self) -> str:
        forwarded_host = (self.headers.get("X-Forwarded-Host") or "").split(",", 1)[0].strip()
        if forwarded_host:
            return forwarded_host

        host = (self.headers.get("Host") or "").strip()
        if host:
            return host
        return f"{LOCAL_API_HOST}:{LOCAL_API_PORT}"

    def _request_base_url(self) -> str:
        return f"{self._request_scheme()}://{self._request_host()}"

    def _build_cover_proxy_url(self, source_url: str | None) -> str | None:
        if not source_url or not is_allowed_cover_url(source_url):
            return source_url

        encoded_url = urllib.parse.quote(source_url, safe="")
        return f"{self._request_base_url()}/api/cover?url={encoded_url}"

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        try:
            if path == "/api/health":
                self._ok(
                    {
                        "service": {
                            "name": "music_local_api",
                            "host": LOCAL_API_HOST,
                            "port": LOCAL_API_PORT,
                        },
                        "runtime": get_runtime_snapshot(),
                        "tasks": TASK_MANAGER.stats(),
                        "proxy": get_current_proxy(),
                    }
                )
                return

            if path == "/api/proxy/current":
                self._ok(get_current_proxy())
                return

            if path == "/api/app/update":
                try:
                    platform = normalize_update_platform((query.get("platform") or ["android"])[0])
                    package_kind = normalize_desktop_package_kind((query.get("kind") or [""])[0]) if platform == "desktop" else ""
                    self._ok(get_app_update_payload(platform, package_kind))
                except ValueError as e:
                    self._error(400, str(e))
                except FileNotFoundError as e:
                    self._error(404, str(e))
                return

            if path == "/api/app/package":
                try:
                    platform = normalize_update_platform((query.get("platform") or ["android"])[0])
                    package_kind = normalize_desktop_package_kind((query.get("kind") or [""])[0]) if platform == "desktop" else ""
                    artifact_path, content_type = get_app_update_artifact(platform, package_kind)
                except ValueError as e:
                    self._error(400, str(e))
                    return
                except FileNotFoundError as e:
                    self._error(404, str(e))
                    return
                self._send_file(
                    artifact_path,
                    os.path.basename(artifact_path),
                    content_type=content_type,
                )
                return

            if path == "/api/app/apk":
                apk_path, _ = get_preferred_android_apk_artifacts()
                if not os.path.exists(apk_path):
                    self._error(404, "apk not found")
                    return
                self._send_file(
                    apk_path,
                    os.path.basename(apk_path),
                    content_type="application/vnd.android.package-archive",
                )
                return

            if path == "/api/tasks":
                self._ok({"tasks": TASK_MANAGER.list_tasks()})
                return

            if path.startswith("/api/tasks/"):
                task_id = path.rsplit("/", 1)[-1]
                task = TASK_MANAGER.get_task(task_id)
                if not task:
                    self._error(404, f"task not found: {task_id}")
                    return
                self._ok(task)
                return

            if path.startswith("/api/files/"):
                task_id = path.rsplit("/", 1)[-1]
                task = TASK_MANAGER.get_task(task_id)
                if not task:
                    self._error(404, f"task not found: {task_id}")
                    return
                file_path = task.get("filePath")
                if not file_path or not os.path.exists(file_path):
                    self._error(404, "file not found")
                    return
                self._send_file(
                    file_path,
                    task.get("filename") or os.path.basename(file_path),
                    content_type="audio/mpeg",
                )
                return

            if path == "/api/logs":
                lines = 100
                try:
                    lines = max(1, min(1000, int((query.get("lines") or ["100"])[0])))
                except Exception:
                    lines = 100
                log_path = os.path.join(get_runtime_snapshot()["baseDir"], "logs", "music_worker.log")
                if not os.path.exists(log_path):
                    self._ok({"lines": []})
                    return
                with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.readlines()[-lines:]
                self._ok({"lines": [line.rstrip("\n") for line in content]})
                return

            if path == "/api/cover":
                source_url = ((query.get("url") or [""])[0] or "").strip()
                if not source_url:
                    self._error(400, "cover url is empty")
                    return
                try:
                    body, content_type = fetch_cover_via_proxy(source_url)
                except ValueError as e:
                    self._error(400, str(e))
                    return
                except FileNotFoundError as e:
                    self._error(404, str(e))
                    return
                self._send_bytes(
                    200,
                    body,
                    content_type=content_type,
                    cache_control="public, max-age=3600",
                )
                return

            self._error(404, f"unknown path: {path}")
        except Exception as e:
            logger.error(f"local api GET failed path={path} err={e}")
            logger.error(traceback.format_exc())
            self._error(500, str(e))

    def do_POST(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path

        try:
            payload = self._read_json()

            if path == "/api/search":
                keyword = (payload.get("keyword") or "").strip()
                limit = payload.get("limit") or 30
                if not keyword:
                    self._error(400, "keyword is empty")
                    return
                try:
                    limit = max(1, min(50, int(limit)))
                except Exception:
                    limit = 30
                results = ytdlp_search(keyword, limit)
                DOWNLOADED_MUSIC_STORE.annotate_search_results(results)
                for item in results:
                    if isinstance(item, dict):
                        item["cover"] = self._build_cover_proxy_url(str(item.get("cover") or "").strip() or None)
                self._ok({"keyword": keyword, "results": results})
                return

            if path == "/api/download":
                music_id = (payload.get("musicId") or payload.get("music_id") or "").strip()
                if not music_id:
                    self._error(400, "musicId is empty")
                    return
                task = TASK_MANAGER.create_download_task(music_id)
                self._ok(task)
                return

            if path == "/api/proxy/select":
                name = (payload.get("name") or "").strip()
                if not name:
                    self._error(400, "name is empty")
                    return
                self._ok(select_proxy(name))
                return

            self._error(404, f"unknown path: {path}")
        except Exception as e:
            logger.error(f"local api POST failed path={path} err={e}")
            logger.error(traceback.format_exc())
            self._error(500, str(e))


def run_server() -> None:
    log_startup_summary()
    logger.info(
        f"music_local_api starting host={LOCAL_API_HOST} port={LOCAL_API_PORT} "
        f"mihomo_controller={MIHOMO_CONTROLLER_URL} selector={MIHOMO_SELECTOR_NAME}"
    )
    server = ThreadingHTTPServer((LOCAL_API_HOST, LOCAL_API_PORT), MusicLocalApiHandler)
    server.serve_forever()


if __name__ == "__main__":
    run_server()
