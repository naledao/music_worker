import os
import sqlite3
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
                        downloaded_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """
                )
                connection.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_downloaded_music_updated_at
                    ON downloaded_music(updated_at DESC)
                    """
                )
                connection.commit()

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

        with self._lock:
            with self._connect() as connection:
                connection.execute(
                    """
                    INSERT INTO downloaded_music (
                        music_id,
                        file_path,
                        filename,
                        file_size,
                        downloaded_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(music_id) DO UPDATE SET
                        file_path = excluded.file_path,
                        filename = excluded.filename,
                        file_size = excluded.file_size,
                        downloaded_at = excluded.downloaded_at,
                        updated_at = excluded.updated_at
                    """,
                    (
                        normalized_music_id,
                        normalized_path,
                        filename,
                        file_size,
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
            "downloadedAt": downloaded_at,
        }

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
                    SELECT music_id, file_path, filename, file_size, downloaded_at, updated_at
                    FROM downloaded_music
                    WHERE music_id IN ({placeholders})
                    """,
                    normalized_ids,
                ).fetchall()

                stale_ids = []
                resolved: dict[str, dict[str, Any]] = {}
                for row in rows:
                    file_path = os.path.abspath((row["file_path"] or "").strip())
                    if not file_path or not os.path.exists(file_path):
                        stale_ids.append(str(row["music_id"]))
                        continue

                    actual_size = os.path.getsize(file_path)
                    resolved[str(row["music_id"])] = {
                        "musicId": str(row["music_id"]),
                        "filePath": file_path,
                        "filename": (row["filename"] or os.path.basename(file_path) or "").strip() or os.path.basename(file_path),
                        "fileSize": actual_size,
                        "downloadedAt": row["downloaded_at"],
                        "updatedAt": row["updated_at"],
                    }
                    if row["file_size"] != actual_size:
                        connection.execute(
                            """
                            UPDATE downloaded_music
                            SET file_size = ?, updated_at = updated_at
                            WHERE music_id = ?
                            """,
                            (actual_size, str(row["music_id"])),
                        )

                if stale_ids:
                    stale_placeholders = ", ".join("?" for _ in stale_ids)
                    connection.execute(
                        f"DELETE FROM downloaded_music WHERE music_id IN ({stale_placeholders})",
                        stale_ids,
                    )
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
        return results
