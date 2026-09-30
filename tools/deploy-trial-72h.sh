#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly REPOSITORY="https://github.com/yusuijiang01-orz/PakRedirect.git"
readonly BRANCH="main"
readonly APP_DIR="/opt/pakredirect-license"
readonly SERVICE="pakredirect-license.service"
readonly HEALTH_URL="http://127.0.0.1:18888/healthz"
readonly BACKUP_ROOT="/var/backups/pakredirect-license/trial-72h"
readonly -a FILES=(
  "user_v1.py"
  "registration_guard_v1.py"
  "admin_web/app.js"
  "admin_web/index.html"
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

if [[ "${EUID}" -ne 0 ]]; then
  fail "Run this script as root (or use sudo)."
fi

if [[ "$#" -gt 1 ]]; then
  fail "Usage: $0 [exact-40-character-commit-SHA]"
fi

if [[ "$#" -eq 1 ]]; then
  [[ "$1" =~ ^[0-9a-fA-F]{40}$ ]] || fail "Commit must be an exact 40-character Git SHA."
  COMMIT="${1,,}"
else
  echo "Resolving latest ${BRANCH} commit..."
  COMMIT="$(git ls-remote "${REPOSITORY}" "refs/heads/${BRANCH}" | awk 'NR == 1 {print $1}')"
  [[ "${COMMIT}" =~ ^[0-9a-f]{40}$ ]] || fail "Could not resolve ${BRANCH} from GitHub."
fi
readonly COMMIT

[[ -d "${APP_DIR}" ]] || fail "Backend directory not found: ${APP_DIR}"
[[ -x "${APP_DIR}/.venv/bin/python" ]] || fail "Backend Python not found: ${APP_DIR}/.venv/bin/python"
[[ -d "${APP_DIR}/admin_web" && ! -L "${APP_DIR}/admin_web" ]] || fail "Expected admin_web to be a real directory under ${APP_DIR}."
id paklicense >/dev/null 2>&1 || fail "Service account paklicense does not exist."
systemctl cat "${SERVICE}" >/dev/null 2>&1 || fail "Systemd unit not found: ${SERVICE}"
for file in "${FILES[@]}"; do
  target="${APP_DIR}/${file}"
  [[ -f "${target}" && ! -L "${target}" ]] || fail "Expected an installed regular file (not a symlink): ${target}"
done

work_dir="$(mktemp -d /tmp/rylux-trial-deploy.XXXXXXXX)"
backup_dir=""
deployment_started=0
rolling_back=0
install_tmp_files=()

cleanup() {
  local file
  for file in "${install_tmp_files[@]}"; do
    rm -f -- "${file}"
  done
  rm -rf -- "${work_dir}"
}

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

atomic_restore() {
  local source="$1"
  local destination="$2"
  local temp="${destination}.rollback.$$"
  install -o paklicense -g paklicense -m 0644 -- "${source}" "${temp}"
  mv -f -- "${temp}" "${destination}"
}

rollback() {
  local failed=0
  local file
  [[ "${deployment_started}" -eq 1 && -n "${backup_dir}" ]] || return 0
  [[ "${rolling_back}" -eq 0 ]] || return 1
  rolling_back=1
  echo "Deployment failed; restoring all four backend files..." >&2
  for file in "${FILES[@]}"; do
    if ! atomic_restore "${backup_dir}/${file}" "${APP_DIR}/${file}"; then
      echo "Could not restore ${APP_DIR}/${file}" >&2
      failed=1
    fi
  done
  if systemctl restart "${SERVICE}" && health_check "${work_dir}/rollback-health.json"; then
    echo "Rollback completed; the previous backend is healthy." >&2
  else
    echo "Rollback files restored, but service health did not recover. Check: systemctl status ${SERVICE}" >&2
    failed=1
  fi
  return "${failed}"
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ "${status}" -ne 0 && "${deployment_started}" -eq 1 ]]; then
    rollback || true
  fi
  cleanup
  exit "${status}"
}
trap on_exit EXIT

echo "[1/6] Fetching source pinned to ${COMMIT}..."
git -C "${work_dir}" init --quiet
git -C "${work_dir}" fetch --quiet --depth=1 --no-tags "${REPOSITORY}" "${COMMIT}"
fetched_commit="$(git -C "${work_dir}" rev-parse FETCH_HEAD^{commit})"
[[ "${fetched_commit}" == "${COMMIT}" ]] || fail "Fetched commit did not match resolved SHA."
for file in "${FILES[@]}"; do
  expected_blob="$(git -C "${work_dir}" rev-parse "${COMMIT}:backend/${file}")"
  mkdir -p -- "${work_dir}/source/$(dirname -- "${file}")"
  git -C "${work_dir}" show "${COMMIT}:backend/${file}" > "${work_dir}/source/${file}"
  actual_blob="$(git -C "${work_dir}" hash-object "${work_dir}/source/${file}")"
  [[ "${actual_blob}" == "${expected_blob}" ]] || fail "Downloaded source did not match the commit tree: backend/${file}"
done

echo "[2/6] Checking Python syntax..."
"${APP_DIR}/.venv/bin/python" -m py_compile \
  "${work_dir}/source/user_v1.py" \
  "${work_dir}/source/registration_guard_v1.py"

echo "[3/6] Backing up all target files..."
install -d -o root -g root -m 0700 "${BACKUP_ROOT}"
backup_dir="$(mktemp -d "${BACKUP_ROOT}/${COMMIT}.XXXXXXXX")"
for file in "${FILES[@]}"; do
  install -D -o root -g root -m 0600 -- "${APP_DIR}/${file}" "${backup_dir}/${file}"
done
chown -R root:root "${backup_dir}"
chmod 0700 "${backup_dir}"

echo "[4/6] Installing the four pinned files..."
deployment_started=1
for file in "${FILES[@]}"; do
  target="${APP_DIR}/${file}"
  temp="${target}.new.$$"
  install_tmp_files+=("${temp}")
  install -o paklicense -g paklicense -m 0644 -- "${work_dir}/source/${file}" "${temp}"
  mv -f -- "${temp}" "${target}"
done

echo "[5/6] Restarting ${SERVICE}..."
systemctl restart "${SERVICE}"

echo "[6/6] Checking the local API health endpoint..."
health_check "${work_dir}/health.json" || {
  systemctl --no-pager --full status "${SERVICE}" || true
  fail "Health check failed."
}

deployment_started=0
echo "Deployment completed."
echo "Commit: ${COMMIT}"
echo "Backup: ${backup_dir}/"
echo "Health check: ${HEALTH_URL} (HTTP 200, RYLUX v1)"
echo "Only backend/user_v1.py, backend/registration_guard_v1.py, backend/admin_web/app.js, and backend/admin_web/index.html were changed."
