#!/usr/bin/env bash
set -Eeuo pipefail

SERVICE="pakredirect-license"
APP_DIR="/opt/pakredirect-license"
VENV="$APP_DIR/.venv"
SERVICE_FILE="/etc/systemd/system/$SERVICE.service"
CONTENT_ENV="/etc/pakredirect-license/content.env"
BASE_URL="https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/backend"
LOG_PREFIX="[RYLUX-REPAIR]"

say() { printf '%s %s\n' "$LOG_PREFIX" "$*"; }
fail() { printf '%s ERROR: %s\n' "$LOG_PREFIX" "$*" >&2; exit 1; }

if [ "$(id -u)" -ne 0 ]; then
  fail "Please run as root: sudo bash temp/repair-rylux-backend.sh"
fi

say "Starting one-click backend repair"

for cmd in curl systemctl install grep; do
  command -v "$cmd" >/dev/null 2>&1 || fail "Missing required command: $cmd"
done

[ -d "$APP_DIR" ] || fail "Missing app directory: $APP_DIR"
[ -x "$VENV/bin/python" ] || fail "Missing virtualenv python: $VENV/bin/python"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$APP_DIR/.repair-backups/$STAMP"
mkdir -p "$BACKUP_DIR"

say "Backing up current backend to $BACKUP_DIR"
for f in app.py protected_content.py requirements.txt; do
  [ -f "$APP_DIR/$f" ] && cp -a "$APP_DIR/$f" "$BACKUP_DIR/$f"
done
[ -f "$SERVICE_FILE" ] && cp -a "$SERVICE_FILE" "$BACKUP_DIR/$SERVICE.service"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

say "Downloading latest backend files from GitHub"
curl -fsSL "$BASE_URL/app.py" -o "$TMP_DIR/app.py"
curl -fsSL "$BASE_URL/protected_content.py" -o "$TMP_DIR/protected_content.py"
curl -fsSL "$BASE_URL/requirements.txt" -o "$TMP_DIR/requirements.txt"
curl -fsSL "$BASE_URL/pakredirect-license.service" -o "$TMP_DIR/$SERVICE.service"

say "Installing backend files"
install -m 0644 -o paklicense -g paklicense "$TMP_DIR/app.py" "$APP_DIR/app.py"
install -m 0644 -o paklicense -g paklicense "$TMP_DIR/protected_content.py" "$APP_DIR/protected_content.py"
install -m 0644 -o paklicense -g paklicense "$TMP_DIR/requirements.txt" "$APP_DIR/requirements.txt"
install -m 0644 -o root -g root "$TMP_DIR/$SERVICE.service" "$SERVICE_FILE"

say "Checking protected-content router is present"
grep -q "from protected_content import router as protected_content_router" "$APP_DIR/app.py" \
  || fail "app.py does not import protected_content router"
grep -q "app.include_router(protected_content_router)" "$APP_DIR/app.py" \
  || fail "app.py does not register protected_content router"

say "Installing Python dependencies"
"$VENV/bin/python" -m pip install --upgrade pip >/dev/null
"$VENV/bin/python" -m pip install -r "$APP_DIR/requirements.txt"

say "Verifying cryptography import"
"$VENV/bin/python" - <<'PY'
import cryptography
print(f"cryptography OK: {cryptography.__version__}")
PY

say "Python syntax/import validation"
cd "$APP_DIR"
"$VENV/bin/python" -m py_compile app.py protected_content.py
"$VENV/bin/python" - <<'PY'
import app
paths = {getattr(r, 'path', None): getattr(r, 'methods', None) for r in app.app.routes}
target = '/api/v1/content/{module_code}/manifest'
if target not in paths:
    raise SystemExit(f'Missing route: {target}')
print('Protected route loaded:', target, paths[target])
PY

if [ -f "$CONTENT_ENV" ]; then
  if grep -q '^RYLUX_SG_LOCALIZATION_KEY_B64=.' "$CONTENT_ENV"; then
    say "Content key environment: CONFIGURED"
  else
    say "WARNING: content.env exists but RYLUX_SG_LOCALIZATION_KEY_B64 is missing/empty"
  fi
else
  say "WARNING: $CONTENT_ENV not found"
fi

say "Reloading systemd and restarting service"
systemctl daemon-reload
systemctl stop "$SERVICE" || true
sleep 1
systemctl reset-failed "$SERVICE" || true
systemctl start "$SERVICE"

say "Waiting for service to become active"
ACTIVE=0
for _ in $(seq 1 15); do
  if systemctl is-active --quiet "$SERVICE"; then
    ACTIVE=1
    break
  fi
  sleep 1
done

if [ "$ACTIVE" -ne 1 ]; then
  say "Service failed to stay active. Recent logs:"
  journalctl -u "$SERVICE" -n 80 --no-pager || true
  fail "Backend repair failed; backup is at $BACKUP_DIR"
fi

PID="$(systemctl show "$SERVICE" -p MainPID --value)"
say "Service active. MainPID=$PID"

say "Checking local health endpoint"
curl -fsS http://127.0.0.1:18888/healthz >/dev/null \
  || fail "Local health check failed"
printf '%s Local health: OK\n' "$LOG_PREFIX"

say "Checking protected manifest route with GET (expected HTTP 405)"
HTTP_CODE="$(curl -sS -o "$TMP_DIR/route.out" -w '%{http_code}' \
  http://127.0.0.1:18888/api/v1/content/sg_localization/manifest || true)"

if [ "$HTTP_CODE" != "405" ]; then
  say "Unexpected local protected-route HTTP code: $HTTP_CODE"
  cat "$TMP_DIR/route.out" 2>/dev/null || true
  say "Recent service logs:"
  journalctl -u "$SERVICE" -n 80 --no-pager || true
  fail "Protected route is not healthy; expected 405"
fi

say "Protected route: OK (HTTP 405 on GET, POST route exists)"

say "Checking public endpoint reachability"
PUBLIC_CODE="$(curl -sS -o /dev/null -w '%{http_code}' \
  https://verify.lovenom.eu.org/api/v1/content/sg_localization/manifest || true)"
printf '%s Public protected route HTTP=%s (expected 405)\n' "$LOG_PREFIX" "$PUBLIC_CODE"

say "Repair completed successfully"
say "Backup retained at: $BACKUP_DIR"
say "Next step: test RYLUX on the phone and tap Start Game again."
