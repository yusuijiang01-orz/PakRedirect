#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly REPOSITORY="https://github.com/yusuijiang01-orz/PakRedirect.git"
readonly APP_DIR="/opt/pakredirect-license"
readonly SERVICE="pakredirect-license.service"
readonly HEALTH_URL="http://127.0.0.1:18888/healthz"
readonly BACKUP_ROOT="/var/backups/pakredirect-license/referral-list"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

if [[ "${EUID}" -ne 0 ]]; then
  fail "Run this script as root (or use sudo)."
fi

if [[ "$#" -ne 1 || ! "$1" =~ ^[0-9a-fA-F]{40}$ ]]; then
  fail "Pass the exact 40-character Git commit SHA containing the backend change."
fi
readonly COMMIT="${1,,}"

[[ -d "${APP_DIR}" ]] || fail "Backend directory not found: ${APP_DIR}"
[[ -x "${APP_DIR}/.venv/bin/python" ]] || fail "Backend Python not found: ${APP_DIR}/.venv/bin/python"
[[ -f "${APP_DIR}/agent_referral.py" ]] || fail "Installed agent_referral.py not found."
[[ ! -L "${APP_DIR}/agent_referral.py" ]] || fail "Refusing to replace a symlink instead of a regular backend file."
id paklicense >/dev/null 2>&1 || fail "Service account paklicense does not exist."
systemctl cat "${SERVICE}" >/dev/null 2>&1 || fail "Systemd unit not found: ${SERVICE}"

work_dir="$(mktemp -d /tmp/rylux-referral-deploy.XXXXXX)"
backup_dir=""
cleanup() {
  rm -rf -- "${work_dir}"
}
trap cleanup EXIT

echo "[1/5] Fetching source pinned to ${COMMIT}..."
git -C "${work_dir}" init --quiet
git -C "${work_dir}" fetch --quiet --depth=1 --no-tags "${REPOSITORY}" "${COMMIT}"
fetched_commit="$(git -C "${work_dir}" rev-parse FETCH_HEAD^{commit})"
[[ "${fetched_commit}" == "${COMMIT}" ]] || fail "Fetched commit did not match requested SHA."
expected_blob="$(git -C "${work_dir}" rev-parse "${COMMIT}:backend/agent_referral.py")"
git -C "${work_dir}" show "${COMMIT}:backend/agent_referral.py" > "${work_dir}/agent_referral.py"
actual_blob="$(git -C "${work_dir}" hash-object "${work_dir}/agent_referral.py")"
[[ "${actual_blob}" == "${expected_blob}" ]] || fail "Downloaded source did not match the commit tree."

echo "[2/5] Checking Python syntax..."
"${APP_DIR}/.venv/bin/python" -m py_compile "${work_dir}/agent_referral.py"

echo "[3/5] Backing up the installed file and installing the pinned file..."
install -d -o root -g root -m 0700 "${BACKUP_ROOT}"
backup_dir="$(mktemp -d "${BACKUP_ROOT}/${COMMIT}.XXXXXXXX")"
cp -a -- "${APP_DIR}/agent_referral.py" "${backup_dir}/agent_referral.py"
chown -R root:root "${backup_dir}"
chmod 0700 "${backup_dir}"

install_tmp="${APP_DIR}/.agent_referral.py.new.$$"
rollback_tmp="${APP_DIR}/.agent_referral.py.rollback.$$"
cleanup_install_files() {
  rm -f -- "${install_tmp}" "${rollback_tmp}"
}
trap 'cleanup_install_files; cleanup' EXIT
install -o paklicense -g paklicense -m 0644 "${work_dir}/agent_referral.py" "${install_tmp}"
mv -f -- "${install_tmp}" "${APP_DIR}/agent_referral.py"

health_check() {
  local response_file="$1"
  local code=""
  for _ in {1..20}; do
    code="$(curl -sS --max-time 3 -o "${response_file}" -w '%{http_code}' "${HEALTH_URL}" || true)"
    if [[ "${code}" == "200" ]] && "${APP_DIR}/.venv/bin/python" - "${response_file}" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)
raise SystemExit(0 if payload.get("ok") is True and payload.get("product") == "RYLUX" and payload.get("api") == "v1" else 1)
PY
    then
      return 0
    fi
    sleep 1
  done
  return 1
}

rollback() {
  echo "Deployment check failed; restoring the previous backend file..." >&2
  install -o paklicense -g paklicense -m 0644 "${backup_dir}/agent_referral.py" "${rollback_tmp}"
  mv -f -- "${rollback_tmp}" "${APP_DIR}/agent_referral.py"
  if systemctl restart "${SERVICE}" && health_check "${work_dir}/rollback-health.json"; then
    echo "Rollback completed; the previous backend is healthy." >&2
  else
    echo "Rollback file restored, but service health did not recover. Check: systemctl status ${SERVICE}" >&2
  fi
  exit 1
}

echo "[4/5] Restarting ${SERVICE}..."
if ! systemctl restart "${SERVICE}"; then
  rollback
fi

echo "[5/5] Checking the local API health endpoint..."
if ! health_check "${work_dir}/health.json"; then
  systemctl --no-pager --full status "${SERVICE}" || true
  rollback
fi

echo "Deployment completed."
echo "Commit: ${COMMIT}"
echo "Backup: ${backup_dir}/agent_referral.py"
echo "Health check: ${HEALTH_URL} (HTTP 200, RYLUX v1)"
echo "No database, configuration, Nginx, or dependency files were changed."
