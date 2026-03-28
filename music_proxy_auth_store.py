import os
import secrets
import sqlite3
import string
import threading
from hmac import compare_digest
from typing import Any


def _generate_password(length: int = 18) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


class ProxyAuthStore:
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
                    CREATE TABLE IF NOT EXISTS proxy_switch_auth (
                        auth_key TEXT PRIMARY KEY,
                        password TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """
                )
                connection.commit()

    def ensure_password(self, updated_at: str) -> dict[str, Any]:
        timestamp = (updated_at or "").strip()
        if not timestamp:
            raise ValueError("updated_at is empty")

        with self._lock:
            with self._connect() as connection:
                row = connection.execute(
                    """
                    SELECT auth_key, password, created_at, updated_at
                    FROM proxy_switch_auth
                    WHERE auth_key = ?
                    """,
                    ("desktop_proxy_switch",),
                ).fetchone()
                if row and str(row["password"] or "").strip():
                    return {
                        "authKey": str(row["auth_key"]),
                        "password": str(row["password"]),
                        "createdAt": str(row["created_at"]),
                        "updatedAt": str(row["updated_at"]),
                        "generated": False,
                    }

                password = _generate_password()
                connection.execute(
                    """
                    INSERT INTO proxy_switch_auth (
                        auth_key,
                        password,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT(auth_key) DO UPDATE SET
                        password = excluded.password,
                        updated_at = excluded.updated_at
                    """,
                    ("desktop_proxy_switch", password, timestamp, timestamp),
                )
                connection.commit()
                return {
                    "authKey": "desktop_proxy_switch",
                    "password": password,
                    "createdAt": timestamp,
                    "updatedAt": timestamp,
                    "generated": True,
                }

    def verify_password(self, password: str) -> bool:
        candidate = (password or "").strip()
        if not candidate:
            return False

        with self._lock:
            with self._connect() as connection:
                row = connection.execute(
                    """
                    SELECT password
                    FROM proxy_switch_auth
                    WHERE auth_key = ?
                    """,
                    ("desktop_proxy_switch",),
                ).fetchone()
                if not row:
                    return False
                stored_password = str(row["password"] or "").strip()
                if not stored_password:
                    return False
                return compare_digest(stored_password, candidate)
