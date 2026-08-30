import csv
import io
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response

from admin_v2 import (
    GeneratePayload,
    MAX_BATCH,
    PRESETS,
    generate_key,
    iso,
    key_hash,
    key_hint,
    license_status,
    log_action,
    normalize_key,
    open_db,
    request_ip,
    require_csrf,
    require_ready,
    utc_now,
)

router = APIRouter()


def init_full_key_support() -> None:
    """Add reversible key storage for newly generated licenses.

    Existing rows created by the old hash-only backend remain NULL because
    their original plaintext cannot be reconstructed from SHA-256.
    """
    with open_db() as db:
        columns = {row["name"] for row in db.execute("PRAGMA table_info(licenses)").fetchall()}
        if "key_value" not in columns:
            db.execute("ALTER TABLE licenses ADD COLUMN key_value TEXT")
        db.execute("CREATE INDEX IF NOT EXISTS idx_licenses_key_value ON licenses(key_value)")
        db.commit()


def serialize_license(row, reveal: bool = False):
    key_value = row["key_value"] if "key_value" in row.keys() else None
    return {
        "id": int(row["id"]),
        "key_hint": row["key_hint"],
        "key_value": key_value if reveal else None,
        "key_available": bool(key_value),
        "label": row["label"] or "",
        "expires_at": row["expires_at"],
        "enabled": bool(row["enabled"]),
        "status": license_status(row),
        "created_at": row["created_at"],
        "last_seen_at": row["last_seen_at"],
        "last_seen_ip": row["last_seen_ip"] or "",
    }


def build_filter(query: str, state: str):
    cond, params = [], []
    q = query.strip()
    if q:
        like = f"%{q}%"
        parts = [
            "label LIKE ? COLLATE NOCASE",
            "key_hint LIKE ? COLLATE NOCASE",
            "COALESCE(last_seen_ip,'') LIKE ? COLLATE NOCASE",
            "COALESCE(key_value,'') LIKE ? COLLATE NOCASE",
        ]
        params.extend([like, like, like, like])
        if normalize_key(q).startswith("PR-"):
            parts.append("key_hash = ?")
            params.append(key_hash(q))
        cond.append("(" + " OR ".join(parts) + ")")

    now = iso(utc_now())
    if state == "active":
        cond.append("enabled=1 AND expires_at>?")
        params.append(now)
    elif state == "disabled":
        cond.append("enabled=0")
    elif state == "expired":
        cond.append("expires_at<=?")
        params.append(now)

    where = (" WHERE " + " AND ".join(cond)) if cond else ""
    return where, params


def list_licenses(query: str, state: str, page: int, page_size: int, reveal: bool = False):
    where, params = build_filter(query, state)
    offset = (page - 1) * page_size
    with open_db() as db:
        total = db.execute("SELECT COUNT(*) AS c FROM licenses" + where, tuple(params)).fetchone()["c"]
        rows = db.execute(
            """
            SELECT id,key_hash,key_hint,key_value,label,expires_at,enabled,created_at,last_seen_at,last_seen_ip
            FROM licenses
            """
            + where
            + " ORDER BY id DESC LIMIT ? OFFSET ?",
            tuple(params + [page_size, offset]),
        ).fetchall()
    return [serialize_license(row, reveal) for row in rows], int(total)


def create_licenses(days: int, quantity: int, label: str):
    if days not in PRESETS:
        raise ValueError("有效期不在允许范围")
    if not 1 <= quantity <= MAX_BATCH:
        raise ValueError(f"生成数量必须在 1 到 {MAX_BATCH} 之间")

    now = utc_now()
    expires = now + timedelta(days=days)
    made = []
    with open_db() as db:
        for _ in range(quantity):
            for _try in range(8):
                value = generate_key()
                try:
                    db.execute(
                        """
                        INSERT INTO licenses
                            (key_hash,key_hint,key_value,label,expires_at,enabled,created_at)
                        VALUES(?,?,?,?,?,1,?)
                        """,
                        (
                            key_hash(value),
                            key_hint(value),
                            value,
                            label.strip()[:80],
                            iso(expires),
                            iso(now),
                        ),
                    )
                    made.append(value)
                    break
                except sqlite3.IntegrityError:
                    continue
            else:
                db.rollback()
                raise RuntimeError("生成唯一卡密失败")
        db.commit()
    return made, iso(expires)


@router.get("/admin/api/licenses")
def admin_licenses(
    request: Request,
    q: str = "",
    status: str = "",
    page: int = 1,
    page_size: int = 30,
    reveal: bool = False,
):
    require_ready(request)
    if status not in ("", "active", "disabled", "expired"):
        status = ""
    page = max(1, min(page, 100000))
    page_size = max(10, min(page_size, 100))
    rows, total = list_licenses(q[:128], status, page, page_size, reveal=reveal)
    return {
        "items": rows,
        "total": total,
        "page": page,
        "page_size": page_size,
        "pages": max(1, (total + page_size - 1) // page_size),
        "reveal": reveal,
        "recoverable": sum(1 for row in rows if row["key_available"]),
    }


@router.post("/admin/api/licenses/generate")
def admin_generate(payload: GeneratePayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    try:
        keys, expires_at = create_licenses(payload.days, payload.quantity, payload.label)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    log_action(
        "licenses_generated",
        f"{payload.quantity} 张",
        f"{payload.days}天; 标签={payload.label[:80]}; full-key=stored",
        request_ip(request),
    )
    return {"ok": True, "keys": keys, "expires_at": expires_at}


@router.get("/admin/api/licenses/export.csv")
def admin_export_csv(request: Request, q: str = "", status: str = ""):
    require_ready(request)
    if status not in ("", "active", "disabled", "expired"):
        status = ""
    where, params = build_filter(q[:128], status)
    with open_db() as db:
        rows = db.execute(
            """
            SELECT id,key_hint,key_value,label,expires_at,enabled,created_at,last_seen_at,last_seen_ip
            FROM licenses
            """ + where + " ORDER BY id DESC",
            tuple(params),
        ).fetchall()

    stream = io.StringIO()
    writer = csv.writer(stream)
    writer.writerow(["ID", "完整卡密", "卡密后6位", "标签", "状态", "到期时间", "最后验证", "最后IP"])
    for row in rows:
        writer.writerow([
            row["id"],
            row["key_value"] or "",
            row["key_hint"],
            row["label"] or "",
            license_status(row),
            row["expires_at"],
            row["last_seen_at"] or "",
            row["last_seen_ip"] or "",
        ])
    return Response(
        "\ufeff" + stream.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": 'attachment; filename="pakredirect-licenses.csv"',
            "Cache-Control": "no-store",
        },
    )


@router.get("/admin/api/licenses/export.txt")
def admin_export_txt(request: Request, q: str = "", status: str = ""):
    require_ready(request)
    if status not in ("", "active", "disabled", "expired"):
        status = ""
    where, params = build_filter(q[:128], status)
    with open_db() as db:
        rows = db.execute(
            "SELECT key_value FROM licenses" + where + " AND key_value IS NOT NULL" if where else
            "SELECT key_value FROM licenses WHERE key_value IS NOT NULL",
            tuple(params),
        ).fetchall()
    text = "\n".join(row["key_value"] for row in rows if row["key_value"])
    return Response(text, media_type="text/plain; charset=utf-8", headers={"Cache-Control": "no-store"})
