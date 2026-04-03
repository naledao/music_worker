import os
import sqlite3
import subprocess
import threading
from typing import Any


class DownloadedMusicStore:
    def __init__(self, db_path: str):
        self._db_path = os.path.abspath(db_path)
        self._lock = threading.Lock()
        os.makedirs(os.path.dirname(self._db_path), exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._lock:
            with self._connect() as connection:
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_music (
                        music_id TEXT PRIMARY KEY,
                        file_path TEXT NOT NULL,
                        filename TEXT,
                        file_size INTEGER,
                        duration_sec REAL,
                        lyrics_path TEXT,
                        lyrics_updated_at TEXT,
                        downloaded_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """
                )
                existing_columns = {
                    row["name"]
                    for row in connection.execute("PRAGMA table_info(downloaded_music)").fetchall()
                }
                if "lyrics_path" not in existing_columns:
                    connection.execute("ALTER TABLE downloaded_music ADD COLUMN lyrics_path TEXT")
                if "lyrics_updated_at" not in existing_columns:
                    connection.execute("ALTER TABLE downloaded_music ADD COLUMN lyrics_updated_at TEXT")
                if "duration_sec" not in existing_columns:
                    connection.execute("ALTER TABLE downloaded_music ADD COLUMN duration_sec REAL")
                connection.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_downloaded_music_updated_at
                    ON downloaded_music(updated_at DESC)
                    """
                )
                connection.commit()

    def _find_sidecar_lyrics_path(self, file_path: str, stored_path: str | None = None) -> str | None:
        normalized_stored_path = os.path.abspath((stored_path or "").strip()) if stored_path else ""
        if normalized_stored_path and os.path.exists(normalized_stored_path):
            return normalized_stored_path

        base_path, _ = os.path.splitext(file_path)
        sidecar_path = f"{base_path}.lrc"
        if os.path.exists(sidecar_path):
            return os.path.abspath(sidecar_path)
        return None

    def _probe_duration_sec(self, file_path: str) -> float | None:
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
                    file_path,
                ],
                capture_output=True,
                text=True,
                timeout=8,
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
            duration_sec = float(raw_duration)
        except Exception:
            return None

        if duration_sec <= 0:
            return None
        return round(duration_sec, 3)

    def _resolve_row_record(
        self,
        connection: sqlite3.Connection,
        row: sqlite3.Row,
    ) -> dict[str, Any] | None:
        normalized_music_id = str(row["music_id"])
        file_path = os.path.abspath((row["file_path"] or "").strip())
        if not file_path or not os.path.exists(file_path):
            connection.execute(
                "DELETE FROM downloaded_music WHERE music_id = ?",
                (normalized_music_id,),
            )
            return None

        actual_size = os.path.getsize(file_path)
        actual_lyrics_path = self._find_sidecar_lyrics_path(file_path, row["lyrics_path"])
        lyrics_updated_at = row["lyrics_updated_at"] if actual_lyrics_path else None
        if actual_lyrics_path and not lyrics_updated_at:
            lyrics_updated_at = row["updated_at"] or row["downloaded_at"]
        stored_duration_sec = row["duration_sec"]
        actual_duration_sec = float(stored_duration_sec) if stored_duration_sec is not None else None
        if row["file_size"] != actual_size or actual_duration_sec is None or actual_duration_sec <= 0:
            probed_duration_sec = self._probe_duration_sec(file_path)
            if probed_duration_sec is not None:
                actual_duration_sec = probed_duration_sec

        if (
            row["file_size"] != actual_size or
            (row["lyrics_path"] or None) != actual_lyrics_path or
            (row["lyrics_updated_at"] or None) != lyrics_updated_at or
            row["duration_sec"] != actual_duration_sec
        ):
            connection.execute(
                """
                UPDATE downloaded_music
                SET file_size = ?, lyrics_path = ?, lyrics_updated_at = ?, duration_sec = ?, updated_at = updated_at
                WHERE music_id = ?
                """,
                (
                    actual_size,
                    actual_lyrics_path,
                    lyrics_updated_at,
                    actual_duration_sec,
                    normalized_music_id,
                ),
            )

        return {
            "musicId": normalized_music_id,
            "filePath": file_path,
            "filename": (row["filename"] or os.path.basename(file_path) or "").strip() or os.path.basename(file_path),
            "displayTitle": os.path.splitext((row["filename"] or os.path.basename(file_path) or "").strip() or os.path.basename(file_path))[0],
            "fileSize": actual_size,
            "durationSec": actual_duration_sec,
            "downloadedAt": row["downloaded_at"],
            "updatedAt": row["updated_at"],
            "lyricsPath": actual_lyrics_path,
            "lyricsExists": actual_lyrics_path is not None,
            "lyricsUpdatedAt": lyrics_updated_at,
        }

    def record_download(
        self,
        music_id: str,
        file_path: str,
        downloaded_at: str,
    ) -> dict[str, Any] | None:
        normalized_music_id = (music_id or "").strip()
        normalized_path = os.path.abspath((file_path or "").strip())
        if not normalized_music_id or not normalized_path or not os.path.exists(normalized_path):
            return None

        filename = os.path.basename(normalized_path)
        file_size = os.path.getsize(normalized_path)
        duration_sec = self._probe_duration_sec(normalized_path)
        lyrics_path = self._find_sidecar_lyrics_path(normalized_path)
        lyrics_updated_at = downloaded_at if lyrics_path else None

        with self._lock:
            with self._connect() as connection:
                connection.execute(
                    """
                    INSERT INTO downloaded_music (
                        music_id,
                        file_path,
                        filename,
                        file_size,
                        duration_sec,
                        lyrics_path,
                        lyrics_updated_at,
                        downloaded_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(music_id) DO UPDATE SET
                        file_path = excluded.file_path,
                        filename = excluded.filename,
                        file_size = excluded.file_size,
                        duration_sec = excluded.duration_sec,
                        lyrics_path = excluded.lyrics_path,
                        lyrics_updated_at = excluded.lyrics_updated_at,
                        downloaded_at = excluded.downloaded_at,
                        updated_at = excluded.updated_at
                    """,
                    (
                        normalized_music_id,
                        normalized_path,
                        filename,
                        file_size,
                        duration_sec,
                        lyrics_path,
                        lyrics_updated_at,
                        downloaded_at,
                        downloaded_at,
                    ),
                )
                connection.commit()

        return {
            "musicId": normalized_music_id,
            "filePath": normalized_path,
            "filename": filename,
            "fileSize": file_size,
            "durationSec": duration_sec,
            "downloadedAt": downloaded_at,
            "lyricsPath": lyrics_path,
            "lyricsExists": lyrics_path is not None,
            "lyricsUpdatedAt": lyrics_updated_at,
        }

    def record_lyrics(
        self,
        music_id: str,
        lyrics_path: str,
        lyrics_updated_at: str,
    ) -> dict[str, Any] | None:
        normalized_music_id = (music_id or "").strip()
        normalized_lyrics_path = os.path.abspath((lyrics_path or "").strip())
        if (
            not normalized_music_id or
            not normalized_lyrics_path or
            not os.path.exists(normalized_lyrics_path)
        ):
            return None

        with self._lock:
            with self._connect() as connection:
                row = connection.execute(
                    """
                    SELECT music_id
                    FROM downloaded_music
                    WHERE music_id = ?
                    """,
                    (normalized_music_id,),
                ).fetchone()
                if row is None:
                    return None

                connection.execute(
                    """
                    UPDATE downloaded_music
                    SET lyrics_path = ?, lyrics_updated_at = ?, updated_at = ?
                    WHERE music_id = ?
                    """,
                    (
                        normalized_lyrics_path,
                        lyrics_updated_at,
                        lyrics_updated_at,
                        normalized_music_id,
                    ),
                )
                connection.commit()

        return self.get_download(normalized_music_id)

    def get_download(self, music_id: str) -> dict[str, Any] | None:
        normalized_music_id = (music_id or "").strip()
        if not normalized_music_id:
            return None

        return self.get_downloads([normalized_music_id]).get(normalized_music_id)

    def get_downloads(self, music_ids: list[str]) -> dict[str, dict[str, Any]]:
        normalized_ids = []
        seen_ids = set()
        for music_id in music_ids:
            normalized_music_id = (music_id or "").strip()
            if not normalized_music_id or normalized_music_id in seen_ids:
                continue
            seen_ids.add(normalized_music_id)
            normalized_ids.append(normalized_music_id)

        if not normalized_ids:
            return {}

        placeholders = ", ".join("?" for _ in normalized_ids)
        with self._lock:
            with self._connect() as connection:
                rows = connection.execute(
                    f"""
                    SELECT music_id, file_path, filename, file_size, duration_sec, lyrics_path, lyrics_updated_at, downloaded_at, updated_at
                    FROM downloaded_music
                    WHERE music_id IN ({placeholders})
                    """,
                    normalized_ids,
                ).fetchall()

                resolved: dict[str, dict[str, Any]] = {}
                for row in rows:
                    record = self._resolve_row_record(connection, row)
                    if record is not None:
                        resolved[str(row["music_id"])] = record
                connection.commit()
                return resolved

    def count_downloads(self) -> int:
        with self._lock:
            with self._connect() as connection:
                row = connection.execute(
                    """
                    SELECT COUNT(*)
                    FROM downloaded_music
                    """
                ).fetchone()
                return int((row[0] if row is not None else 0) or 0)

    def list_downloads(self, limit: int | None = None, offset: int = 0) -> list[dict[str, Any]]:
        query = """
            SELECT music_id, file_path, filename, file_size, duration_sec, lyrics_path, lyrics_updated_at, downloaded_at, updated_at
            FROM downloaded_music
            ORDER BY downloaded_at DESC, updated_at DESC
        """
        params: tuple[Any, ...] = ()
        safe_offset = max(0, int(offset or 0))
        if limit is not None and int(limit) > 0:
            query += " LIMIT ? OFFSET ?"
            params = (int(limit), safe_offset)

        with self._lock:
            with self._connect() as connection:
                rows = connection.execute(query, params).fetchall()
                resolved: list[dict[str, Any]] = []
                for row in rows:
                    record = self._resolve_row_record(connection, row)
                    if record is not None:
                        resolved.append(record)
                connection.commit()
                return resolved

    def annotate_search_results(self, results: list[dict[str, Any]]) -> list[dict[str, Any]]:
        downloads = self.get_downloads([
            str(item.get("id") or "").strip()
            for item in results
            if isinstance(item, dict)
        ])

        for item in results:
            if not isinstance(item, dict):
                continue
            record = downloads.get(str(item.get("id") or "").strip())
            item["downloaded"] = record is not None
            item["downloadedFilePath"] = record.get("filePath") if record else None
            item["downloadedFileSize"] = record.get("fileSize") if record else None
            item["downloadedAt"] = record.get("downloadedAt") if record else None
            item["lyricsExists"] = bool(record.get("lyricsExists")) if record else False
        return results
