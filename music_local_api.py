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
)
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
    package_version = "0.1.0"
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


def get_preferred_desktop_installer_artifact() -> tuple[str, str]:
    package_name, package_version = get_desktop_distribution_settings()
    base_dir = repo_path("desktop-app", "build", "compose", "binaries", "main")
    preferred_candidates = [
        os.path.join(base_dir, "msi", f"{package_name}-{package_version}.msi"),
        os.path.join(base_dir, "exe", f"{package_name}-{package_version}.exe"),
    ]
    for candidate in preferred_candidates:
        if os.path.exists(candidate):
            return candidate, guess_update_content_type(candidate)

    fallback_patterns = [
        os.path.join(base_dir, "msi", "*.msi"),
        os.path.join(base_dir, "exe", "*.exe"),
    ]
    for pattern in fallback_patterns:
        matches = sorted(glob.glob(pattern), reverse=True)
        if matches:
            return matches[0], guess_update_content_type(matches[0])

    raise FileNotFoundError(f"desktop installer not found under: {base_dir}")


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


def get_desktop_app_update_payload() -> dict[str, Any]:
    installer_path, _ = get_preferred_desktop_installer_artifact()
    package_name, package_version = get_desktop_distribution_settings()
    file_name = os.path.basename(installer_path)
    return {
        "versionCode": None,
        "versionName": package_version,
        "fileName": file_name,
        "fileSize": os.path.getsize(installer_path),
        "sha256": sha256_file(installer_path),
        "updatedAt": iso_ts(os.path.getmtime(installer_path)),
        "downloadPath": "/api/app/package?platform=desktop",
    }


def normalize_update_platform(platform_raw: str | None) -> str:
    platform = (platform_raw or "android").strip().lower()
    if platform in ("", "android"):
        return "android"
    if platform in ("desktop", "windows", "win"):
        return "desktop"
    raise ValueError(f"unsupported update platform: {platform_raw}")


def get_app_update_payload(platform: str = "android") -> dict[str, Any]:
    if platform == "desktop":
        return get_desktop_app_update_payload()
    return get_android_app_update_payload()


def get_app_update_artifact(platform: str = "android") -> tuple[str, str]:
    if platform == "desktop":
        return get_preferred_desktop_installer_artifact()
    apk_path, _ = get_preferred_android_apk_artifacts()
    return apk_path, guess_update_content_type(apk_path)


class TaskManager:
    def __init__(self, max_workers: int):
        self._tasks: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="music-local-api")

    def _touch(self, task: dict[str, Any]) -> None:
        task["updatedAt"] = iso_ts()

    def _copy_task(self, task: dict[str, Any]) -> dict[str, Any]:
        return json.loads(json.dumps(task, ensure_ascii=False))

    def create_download_task(self, music_id: str) -> dict[str, Any]:
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
        with self._lock:
            self._tasks[task_id] = task
        self._executor.submit(self._run_download, task_id, music_id)
        return self.get_task(task_id) or task

    def update_task(self, task_id: str, **fields: Any) -> None:
        with self._lock:
            task = self._tasks.get(task_id)
            if not task:
                return
            task.update(fields)
            self._touch(task)

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
            self.update_task(
                task_id,
                status="finished",
                stage="finished",
                progress=100,
                filename=os.path.basename(path),
                filePath=path,
                fileSize=os.path.getsize(path),
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
                    self._ok(get_app_update_payload(platform))
                except ValueError as e:
                    self._error(400, str(e))
                except FileNotFoundError as e:
                    self._error(404, str(e))
                return

            if path == "/api/app/package":
                try:
                    platform = normalize_update_platform((query.get("platform") or ["android"])[0])
                    artifact_path, content_type = get_app_update_artifact(platform)
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
