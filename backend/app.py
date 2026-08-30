import hashlib
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

DB_PATH = Path(os.environ.get("PAKREDIRECT_LICENSE_DB", "./data/licenses.db")).resolve()

app = FastAPI(
    title="PakRedirect License API",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


class VerifyRequest(BaseModel):
    license_key: str = Field(min_length=4, max_length=128)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def normalize_key(value: str) -> str:
    return value.strip().upper()


def key_hash(value: str) -> str:
    return hashlib.sha256(normalize_key(value).encode("utf-8")).hexdigest()


def open_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=5)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA busy_timeout=5000")
    return connection


def init_db() -> None:
    with open_db() as db:
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS licenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key_hash TEXT NOT NULL UNIQUE,
                key_hint TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                expires_at TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL,
                last_seen_at TEXT,
                last_seen_ip TEXT
            )
            """
        )
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_licenses_expires_at ON licenses(expires_at)"
        )
        db.commit()


@app.on_event("startup")
def on_startup() -> None:
    init_db()


@app.get("/healthz")
def healthz():
    return {"ok": True}


@app.post("/api/v1/license/verify")
def verify_license(payload: VerifyRequest, request: Request):
    normalized = normalize_key(payload.license_key)
    digest = key_hash(normalized)

    with open_db() as db:
        row = db.execute(
            """
            SELECT id, expires_at, enabled
            FROM licenses
            WHERE key_hash = ?
            LIMIT 1
            """,
            (digest,),
        ).fetchone()

        if row is None:
            return {
                "valid": False,
                "expires_at": None,
                "message": "卡密无效",
            }

        if int(row["enabled"]) != 1:
            return {
                "valid": False,
                "expires_at": row["expires_at"],
                "message": "卡密已停用",
            }

        try:
            expires_at = datetime.fromisoformat(row["expires_at"].replace("Z", "+00:00"))
        except ValueError:
            return {
                "valid": False,
                "expires_at": row["expires_at"],
                "message": "卡密数据异常",
            }

        if expires_at <= utc_now():
            return {
                "valid": False,
                "expires_at": row["expires_at"],
                "message": "卡密已到期",
            }

        client_ip = request.headers.get("x-real-ip")
        if not client_ip:
            client_ip = request.client.host if request.client else ""

        now_text = utc_now().isoformat().replace("+00:00", "Z")
        db.execute(
            """
            UPDATE licenses
            SET last_seen_at = ?, last_seen_ip = ?
            WHERE id = ?
            """,
            (now_text, client_ip, row["id"]),
        )
        db.commit()

        return {
            "valid": True,
            "expires_at": row["expires_at"],
            "message": "验证成功",
        }
