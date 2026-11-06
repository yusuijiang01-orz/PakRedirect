"""Shared formatting for redeemable license codes."""

import secrets


PREFIX_BY_DAYS = {
    30: "MO",   # monthly
    90: "QT",   # quarterly
    180: "HY",  # half-year
    360: "YR",  # yearly admin preset
    365: "YR",  # yearly agent preset
}
LICENSE_KEY_PREFIXES = ("PR", "MO", "QT", "HY", "YR")


def generate_key(days: int | None = None) -> str:
    raw = secrets.token_hex(10).upper()
    prefix = PREFIX_BY_DAYS.get(days, "PR")
    groups = "-".join(raw[i:i + 4] for i in range(0, len(raw), 4))
    return f"{prefix}-{groups}"


def is_license_key_prefix(value: str) -> bool:
    normalized = value.strip().upper()
    return normalized.startswith(tuple(f"{prefix}-" for prefix in LICENSE_KEY_PREFIXES))
