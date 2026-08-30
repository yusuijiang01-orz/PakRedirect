import argparse
import base64
import getpass
import hashlib
import os
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

DB_PATH = Path(os.environ.get("PAKREDIRECT_LICENSE_DB", "./data/licenses.db")).resolve()
PBKDF2_ITERATIONS = 310_000


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_key(value: str) -> str:
    return value.strip().upper()


def key_hash(value: str) -> str:
    return hashlib.sha256(normalize_key(value).encode("utf-8")).hexdigest()


def key_hint(value: str) -> str:
    compact = normalize_key(value).replace("-", "")
    return compact[-6:] if len(compact) >= 6 else compact


def open_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
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
    return db


def generate_key() -> str:
    raw = secrets.token_hex(10).upper()
    groups = [raw[i:i + 4] for i in range(0, len(raw), 4)]
    return "PR-" + "-".join(groups)


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def make_admin_password_hash(password: str) -> str:
    if len(password) < 10:
        raise SystemExit("管理员密码至少 10 个字符")
    salt = secrets.token_bytes(18)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        PBKDF2_ITERATIONS,
    )
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${b64url(salt)}${b64url(digest)}"


def create_license(days: int, label: str, requested_key: str | None) -> None:
    if days <= 0:
        raise SystemExit("--days 必须大于 0")

    value = normalize_key(requested_key) if requested_key else generate_key()
    expires = utc_now() + timedelta(days=days)

    with open_db() as db:
        try:
            db.execute(
                """
                INSERT INTO licenses
                    (key_hash, key_hint, label, expires_at, enabled, created_at)
                VALUES (?, ?, ?, ?, 1, ?)
                """,
                (
                    key_hash(value),
                    key_hint(value),
                    label or "",
                    iso(expires),
                    iso(utc_now()),
                ),
            )
            db.commit()
        except sqlite3.IntegrityError:
            raise SystemExit("该卡密已存在")

    print("卡密:", value)
    print("到期:", iso(expires))
    print("提示: 服务端只保存 SHA-256 哈希，完整卡密只在这里显示一次。")


def revoke_license(value: str) -> None:
    with open_db() as db:
        cur = db.execute(
            "UPDATE licenses SET enabled = 0 WHERE key_hash = ?",
            (key_hash(value),),
        )
        db.commit()
    if cur.rowcount != 1:
        raise SystemExit("未找到卡密")
    print("已停用")


def enable_license(value: str) -> None:
    with open_db() as db:
        cur = db.execute(
            "UPDATE licenses SET enabled = 1 WHERE key_hash = ?",
            (key_hash(value),),
        )
        db.commit()
    if cur.rowcount != 1:
        raise SystemExit("未找到卡密")
    print("已启用")


def extend_license(value: str, days: int) -> None:
    if days <= 0:
        raise SystemExit("--days 必须大于 0")

    digest = key_hash(value)
    with open_db() as db:
        row = db.execute(
            "SELECT expires_at FROM licenses WHERE key_hash = ?",
            (digest,),
        ).fetchone()
        if row is None:
            raise SystemExit("未找到卡密")

        current = datetime.fromisoformat(row["expires_at"].replace("Z", "+00:00"))
        base = max(current, utc_now())
        new_expiry = base + timedelta(days=days)
        db.execute(
            "UPDATE licenses SET expires_at = ? WHERE key_hash = ?",
            (iso(new_expiry), digest),
        )
        db.commit()
    print("新到期时间:", iso(new_expiry))


def list_licenses() -> None:
    with open_db() as db:
        rows = db.execute(
            """
            SELECT id, key_hint, label, expires_at, enabled, created_at,
                   last_seen_at, last_seen_ip
            FROM licenses
            ORDER BY id DESC
            """
        ).fetchall()

    if not rows:
        print("暂无卡密")
        return

    for row in rows:
        status = "启用" if int(row["enabled"]) == 1 else "停用"
        print(
            f"#{row['id']}  ***{row['key_hint']}  {status}  "
            f"到期={row['expires_at']}  标签={row['label'] or '-'}  "
            f"最后验证={row['last_seen_at'] or '-'}  IP={row['last_seen_ip'] or '-'}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="PakRedirect 卡密管理")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create", help="创建卡密")
    create.add_argument("--days", type=int, default=30)
    create.add_argument("--label", default="")
    create.add_argument("--key", default=None, help="可选：自定义卡密")

    revoke = sub.add_parser("revoke", help="停用卡密")
    revoke.add_argument("key")

    enable = sub.add_parser("enable", help="重新启用卡密")
    enable.add_argument("key")

    extend = sub.add_parser("extend", help="续期卡密")
    extend.add_argument("key")
    extend.add_argument("--days", type=int, required=True)

    sub.add_parser("list", help="列出卡密")

    admin_hash = sub.add_parser("admin-hash", help="生成网页后台管理员密码哈希")
    admin_hash.add_argument("--password", default=None, help="可选；不建议留在 shell history")

    sub.add_parser("session-secret", help="生成网页后台会话密钥")

    args = parser.parse_args()
    if args.command == "create":
        create_license(args.days, args.label, args.key)
    elif args.command == "revoke":
        revoke_license(args.key)
    elif args.command == "enable":
        enable_license(args.key)
    elif args.command == "extend":
        extend_license(args.key, args.days)
    elif args.command == "list":
        list_licenses()
    elif args.command == "admin-hash":
        password = args.password
        if password is None:
            password = getpass.getpass("管理员密码: ")
            confirm = getpass.getpass("再次输入: ")
            if password != confirm:
                raise SystemExit("两次输入的密码不一致")
        print(make_admin_password_hash(password))
    elif args.command == "session-secret":
        print(secrets.token_urlsafe(48))


if __name__ == "__main__":
    main()
