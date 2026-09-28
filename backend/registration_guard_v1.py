import base64
import hashlib
import hmac
import secrets
import sqlite3
import struct
import zlib
from datetime import timedelta

from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import Field

from admin_v2 import request_ip
from user_v1 import (
    RegisterPayload,
    TRIAL_HOURS,
    create_session,
    digest_device,
    iso,
    open_db,
    password_hash,
    username_key,
    utc_now,
    validate_username,
)
from agent_referral import record_registration

router = APIRouter()

# Device identity is the primary anti-abuse signal. IP is only an auxiliary
# risk signal so shared Wi-Fi / carrier NAT does not block account creation.
IP_TRIAL_WINDOW_HOURS = 48
IP_TRIAL_MAX_AWARDS = 3
CAPTCHA_TTL_SECONDS = 180
CAPTCHA_MAX_ATTEMPTS = 5
CAPTCHA_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


class GuardedRegisterPayload(RegisterPayload):
    captcha_id: str = Field(default="", max_length=128)
    captcha_code: str = Field(default="", max_length=8)


def digest_ip(value: str) -> str:
    value = (value or "").strip()
    if not value:
        return ""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def init_registration_guard_v1() -> None:
    with open_db() as db:
        columns = {row["name"] for row in db.execute("PRAGMA table_info(app_users)").fetchall()}
        if "registration_ip_hash" not in columns:
            db.execute("ALTER TABLE app_users ADD COLUMN registration_ip_hash TEXT")
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_app_users_registration_ip_hash ON app_users(registration_ip_hash)"
        )

        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS trial_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL UNIQUE,
                device_hash TEXT NOT NULL DEFAULT '',
                ip_hash TEXT NOT NULL DEFAULT '',
                awarded INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS auth_captcha_challenges (
                challenge_id TEXT PRIMARY KEY,
                answer_salt TEXT NOT NULL,
                answer_hash TEXT NOT NULL,
                ip_hash TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                consumed_at TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_auth_captcha_expires
                ON auth_captcha_challenges(expires_at);
            CREATE INDEX IF NOT EXISTS idx_trial_claims_device_awarded
                ON trial_claims(device_hash, awarded);
            CREATE INDEX IF NOT EXISTS idx_trial_claims_ip_created
                ON trial_claims(ip_hash, created_at, awarded);
            """
        )

        # Preserve old registrations when upgrading: an account that previously
        # received trial time is treated as an already-used device trial where
        # a device hash is available. This prevents an upgrade from reopening
        # free-trial eligibility for existing devices.
        rows = db.execute(
            """
            SELECT id,last_device_hash,registration_ip_hash,
                   trial_started_at,trial_expires_at,created_at
            FROM app_users
            WHERE id NOT IN (SELECT user_id FROM trial_claims)
            """
        ).fetchall()
        for row in rows:
            trial_awarded = int(
                bool(row["trial_expires_at"])
                and bool(row["trial_started_at"])
                and row["trial_expires_at"] > row["trial_started_at"]
            )
            db.execute(
                """
                INSERT OR IGNORE INTO trial_claims
                    (user_id,device_hash,ip_hash,awarded,created_at)
                VALUES(?,?,?,?,?)
                """,
                (
                    row["id"],
                    (row["last_device_hash"] or "").strip(),
                    (row["registration_ip_hash"] or "").strip(),
                    trial_awarded,
                    row["created_at"],
                ),
            )

        db.execute(
            """
            UPDATE modules
            SET name='封神榜汉化', description='RYLUX 本地汉化模块'
            WHERE code='sg_localization'
            """
        )
        db.commit()


def _trial_allowed(db, device_hash: str, ip_hash: str, cutoff_text: str) -> bool:
    # A known device can receive a trial only once. When the client cannot
    # provide a device ID, the rolling IP quota is the fallback signal.
    if not device_hash and not ip_hash:
        return False

    if device_hash:
        used_device = db.execute(
            """
            SELECT 1 FROM trial_claims
            WHERE device_hash=? AND awarded=1
            LIMIT 1
            """,
            (device_hash,),
        ).fetchone()
        if used_device is not None:
            return False

    # Shared IPs may have multiple legitimate devices, so cap only the number
    # of trial awards per IP over 48 hours rather than blocking registration.
    if ip_hash:
        recent_ip_awards = db.execute(
            """
            SELECT COUNT(*) AS n FROM trial_claims
            WHERE ip_hash=? AND awarded=1 AND created_at>?
            """,
            (ip_hash, cutoff_text),
        ).fetchone()
        if recent_ip_awards is not None and int(recent_ip_awards["n"] or 0) >= IP_TRIAL_MAX_AWARDS:
            return False

    return True


def _png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)


_CAPTCHA_FONT = {
    "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    "3": ("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
    "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    "5": ("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
    "6": ("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
    "7": ("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    "8": ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    "9": ("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "J": ("00111", "00010", "00010", "00010", "10010", "10010", "01100"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "Q": ("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "W": ("10001", "10001", "10001", "10101", "10101", "10101", "01010"),
    "X": ("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
}


def _captcha_png(answer: str) -> str:
    # Draw a small noisy raster image using only the standard library. The
    # answer never appears as text or SVG markup in the API response.
    rng = secrets.SystemRandom()
    width, height = 168, 58
    pixels = [[(245, 247, 250) for _ in range(width)] for _ in range(height)]

    def point(x: int, y: int, color: tuple[int, int, int]) -> None:
        if 0 <= x < width and 0 <= y < height:
            pixels[y][x] = color

    for _ in range(4):
        x0, y0 = rng.randrange(width), rng.randrange(height)
        x1, y1 = rng.randrange(width), rng.randrange(height)
        steps = max(abs(x1 - x0), abs(y1 - y0), 1)
        color = (rng.randrange(170, 215), rng.randrange(185, 225), rng.randrange(205, 235))
        for step in range(steps + 1):
            x = round(x0 + (x1 - x0) * step / steps)
            y = round(y0 + (y1 - y0) * step / steps)
            point(x, y, color)

    for _ in range(190):
        point(rng.randrange(width), rng.randrange(height),
              (rng.randrange(175, 220), rng.randrange(185, 225), rng.randrange(195, 235)))

    colors = ((35, 63, 125), (81, 55, 117), (26, 99, 104), (119, 65, 48))
    for index, char in enumerate(answer):
        glyph = _CAPTCHA_FONT[char]
        origin_x = 9 + index * 39
        origin_y = 12 + rng.randrange(-3, 4)
        color = colors[rng.randrange(len(colors))]
        row_skew = rng.choice((-1, 0, 0, 0, 1))
        for row, pattern in enumerate(glyph):
            jitter = round((row - 3) * row_skew / 3)
            for col, bit in enumerate(pattern):
                if bit == "1":
                    for dy in range(4):
                        for dx in range(4):
                            point(origin_x + col * 4 + dx + jitter, origin_y + row * 4 + dy, color)

    raw = b"".join(b"\x00" + b"".join(bytes(pixel) for pixel in row) for row in pixels)
    png = (b"\x89PNG\r\n\x1a\n"
           + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
           + _png_chunk(b"IDAT", zlib.compress(raw, 6))
           + _png_chunk(b"IEND", b""))
    return base64.b64encode(png).decode("ascii")


@router.get("/api/v1/auth/captcha")
def create_captcha(request: Request, response: Response):
    response.headers["Cache-Control"] = "no-store, max-age=0"
    response.headers["Pragma"] = "no-cache"
    now = utc_now()
    challenge_id = secrets.token_urlsafe(24)
    answer = "".join(secrets.choice(CAPTCHA_ALPHABET) for _ in range(4))
    answer_salt = secrets.token_hex(16)
    answer_hash = hashlib.sha256((answer_salt + answer).encode("ascii")).hexdigest()
    ip_hash = digest_ip(request_ip(request))
    expires = now + timedelta(seconds=CAPTCHA_TTL_SECONDS)
    image_base64 = _captcha_png(answer)

    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        db.execute("DELETE FROM auth_captcha_challenges WHERE created_at<?",
                   (iso(now - timedelta(days=1)),))
        db.execute(
            """
            INSERT INTO auth_captcha_challenges
                (challenge_id,answer_salt,answer_hash,ip_hash,created_at,expires_at,attempts)
            VALUES(?,?,?,?,?,?,0)
            """,
            (challenge_id, answer_salt, answer_hash, ip_hash, iso(now), iso(expires)),
        )
        db.commit()

    return {
        "ok": True,
        "challenge_id": challenge_id,
        "image_base64": image_base64,
        "expires_in_seconds": CAPTCHA_TTL_SECONDS,
    }


def _consume_captcha(challenge_id: str, answer: str, ip_hash: str, now_text: str) -> None:
    if not challenge_id or len(answer.strip()) != 4:
        raise HTTPException(status_code=400, detail="验证码错误或已过期，请刷新")

    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        row = db.execute(
            "SELECT * FROM auth_captcha_challenges WHERE challenge_id=? LIMIT 1",
            (challenge_id.strip(),),
        ).fetchone()
        if (row is None or row["consumed_at"] is not None or row["expires_at"] <= now_text
                or int(row["attempts"]) >= CAPTCHA_MAX_ATTEMPTS or row["ip_hash"] != ip_hash):
            db.rollback()
            raise HTTPException(status_code=400, detail="验证码错误或已过期，请刷新")

        submitted_hash = hashlib.sha256(
            (row["answer_salt"] + answer.strip().upper()).encode("ascii", errors="ignore")
        ).hexdigest()
        if not hmac.compare_digest(submitted_hash, row["answer_hash"]):
            db.execute(
                "UPDATE auth_captcha_challenges SET attempts=attempts+1 WHERE challenge_id=?",
                (challenge_id.strip(),),
            )
            db.commit()
            raise HTTPException(status_code=400, detail="验证码错误，请重试或换一张")

        db.execute(
            "UPDATE auth_captcha_challenges SET consumed_at=? WHERE challenge_id=?",
            (now_text, challenge_id.strip()),
        )
        db.commit()


@router.post("/api/v1/auth/register")
def guarded_register(payload: GuardedRegisterPayload, request: Request):
    try:
        username = validate_username(payload.username)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    now = utc_now()
    ip = request_ip(request).strip()
    ip_hash = digest_ip(ip)
    _consume_captcha(payload.captcha_id, payload.captcha_code, ip_hash, iso(now))

    try:
        encoded = password_hash(payload.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    cutoff = now - timedelta(hours=IP_TRIAL_WINDOW_HOURS)
    device_hash = digest_device(payload.device_id)

    with open_db() as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            trial_allowed = _trial_allowed(db, device_hash, ip_hash, iso(cutoff))
            trial_expires = now + timedelta(hours=TRIAL_HOURS) if trial_allowed else now

            cur = db.execute(
                """
                INSERT INTO app_users
                    (username,username_key,password_hash,status,vip_level,vip_expires_at,
                     trial_started_at,trial_expires_at,created_at,updated_at,
                     last_login_at,last_login_ip,last_device_hash,login_count,registration_ip_hash)
                VALUES(?,?,?,1,1,?,?,?,?,?,?,?,?,1,?)
                """,
                (
                    username,
                    username_key(username),
                    encoded,
                    iso(trial_expires),
                    iso(now),
                    iso(trial_expires),
                    iso(now),
                    iso(now),
                    iso(now),
                    ip[:64],
                    device_hash,
                    ip_hash,
                ),
            )
        except sqlite3.IntegrityError:
            db.rollback()
            raise HTTPException(status_code=409, detail="用户名已存在")

        user_id = int(cur.lastrowid)
        db.execute(
            """
            INSERT INTO trial_claims
                (user_id,device_hash,ip_hash,awarded,created_at)
            VALUES(?,?,?,?,?)
            """,
            (user_id, device_hash, ip_hash, 1 if trial_allowed else 0, iso(now)),
        )

        if trial_allowed:
            db.execute(
                """
                INSERT INTO vip_events
                    (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
                VALUES(?,?,?,?,?,?,?)
                """,
                (
                    user_id,
                    "trial",
                    TRIAL_HOURS * 3600,
                    "new-user",
                    None,
                    iso(trial_expires),
                    iso(now),
                ),
            )

        record_registration(db, user_id, payload.invite_code, trial_allowed, device_hash, ip_hash, now)

        token, session_expires = create_session(db, user_id, ip, device_hash)
        db.commit()

    return {
        "ok": True,
        "token": token,
        "session_expires_at": session_expires,
        "user": {
            "id": user_id,
            "username": username,
            "membership": {
                "active": trial_allowed,
                "kind": "trial" if trial_allowed else "expired",
                "vip_level": 1,
                "expires_at": iso(trial_expires),
                "trial_expires_at": iso(trial_expires),
            },
        },
        "message": (
            "注册成功，已获得 24 小时体验时间"
            if trial_allowed
            else "注册成功，本次未获得免费体验时间"
        ),
    }
