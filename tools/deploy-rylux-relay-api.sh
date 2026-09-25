#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly REPOSITORY="https://github.com/yusuijiang01-orz/PakRedirect.git"
readonly SOURCE_BRANCH="main"
readonly APP_DIR="/opt/pakredirect-license"
readonly ENV_DIR="/etc/pakredirect-license"
readonly ENV_FILE="${ENV_DIR}/relay.env"
readonly HEALTH_URL="https://verify.lovenom.eu.org/healthz"
readonly RELAY_URL="https://verify.lovenom.eu.org/api/v1/modules/sg_localization/relay-token"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root (or use sudo)." >&2
  exit 1
fi

if [[ ! -d "${APP_DIR}" || ! -x "${APP_DIR}/.venv/bin/pip" ]]; then
  echo "Backend directory or virtualenv not found: ${APP_DIR}" >&2
  exit 1
fi

if [[ ! -r /dev/tty ]]; then
  echo "An interactive SSH terminal is required to enter the relay token." >&2
  exit 1
fi

work_dir="$(mktemp -d /tmp/rylux-relay-deploy.XXXXXX)"
token_tmp=""
cleanup() {
  if [[ -n "${token_tmp}" ]]; then
    rm -f -- "${token_tmp}"
  fi
  rm -rf -- "${work_dir}"
  unset TOKEN 2>/dev/null || true
}
trap cleanup EXIT

echo "[1/4] Downloading backend source..."
git clone --depth 1 --single-branch --branch "${SOURCE_BRANCH}" \
  "${REPOSITORY}" "${work_dir}/source"
source_dir="${work_dir}/source/backend"

echo "[2/4] Installing backend files..."
for file in app.py admin_v2.py admin_key_access.py admin_code_v1.py \
  user_v1.py registration_guard_v1.py manage.py requirements.txt; do
  install -o paklicense -g paklicense -m 0644 "${source_dir}/${file}" "${APP_DIR}/${file}"
done

install -o root -g root -m 0644 "${source_dir}/pakredirect-license.service" \
  /etc/systemd/system/pakredirect-license.service
install -o root -g root -m 0644 "${source_dir}/nginx-pakredirect-license.conf" \
  /etc/nginx/sites-available/pakredirect-license

admin_web_tmp="${APP_DIR}/.admin_web.new.$$"
rm -rf -- "${admin_web_tmp}"
cp -a "${source_dir}/admin_web" "${admin_web_tmp}"
chown -R paklicense:paklicense "${admin_web_tmp}"
rm -rf -- "${APP_DIR}/admin_web"
mv -- "${admin_web_tmp}" "${APP_DIR}/admin_web"

chown paklicense:paklicense "${APP_DIR}/requirements.txt"
"${APP_DIR}/.venv/bin/pip" install -r "${APP_DIR}/requirements.txt"

echo
echo "[3/4] Enter the same RYLUX_RELAY_TOKEN configured in the Cloudflare Worker."
IFS= read -r -s -p "RYLUX_RELAY_TOKEN: " TOKEN </dev/tty
printf '\n' >/dev/tty
if [[ -z "${TOKEN}" ]]; then
  echo "Token is empty; deployment stopped." >&2
  exit 1
fi

install -d -o root -g root -m 0700 "${ENV_DIR}"
token_tmp="$(mktemp "${ENV_DIR}/.relay.env.XXXXXX")"
printf 'RYLUX_RELAY_TOKEN=%s\n' "${TOKEN}" > "${token_tmp}"
unset TOKEN
chown root:root "${token_tmp}"
chmod 0600 "${token_tmp}"
mv -f -- "${token_tmp}" "${ENV_FILE}"
token_tmp=""

if ! grep -Eq '^RYLUX_RELAY_TOKEN=.+$' "${ENV_FILE}"; then
  echo "The relay token file is empty; deployment stopped." >&2
  exit 1
fi

echo "[4/4] Restarting services and checking endpoints..."
nginx -t
systemctl daemon-reload
systemctl restart pakredirect-license
systemctl reload nginx

health_code=""
for attempt in {1..15}; do
  health_code="$(curl -sS -o "${work_dir}/health.json" -w '%{http_code}' \
    --max-time 5 "${HEALTH_URL}" || true)"
  [[ "${health_code}" == "200" ]] && break
  sleep 1
done

if [[ "${health_code}" != "200" ]]; then
  echo "Health check failed (HTTP ${health_code:-no response})." >&2
  systemctl --no-pager --full status pakredirect-license || true
  exit 1
fi

cat "${work_dir}/health.json"
echo

relay_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
  "${RELAY_URL}" || true)"
if [[ "${relay_code}" != "401" ]]; then
  echo "Relay-token route check failed (HTTP ${relay_code:-no response}; expected 401 without login)." >&2
  exit 1
fi

echo "Relay-token route is online (401 without login is expected)."
echo "Deployment completed."
