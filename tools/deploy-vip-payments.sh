#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly REPOSITORY="https://github.com/yusuijiang01-orz/PakRedirect.git"
readonly BRANCH="main"
readonly APP_DIR="/opt/pakredirect-license"
readonly SERVICE="pakredirect-license.service"
readonly HEALTH_URL="http://127.0.0.1:18888/healthz"
readonly PLANS_URL="http://127.0.0.1:18888/api/v1/plans"
readonly ADMIN_ASSET_URL="http://127.0.0.1:18888/admin/assets/app.js"
readonly BACKUP_ROOT="/var/backups/pakredirect-license/vip-payments"
readonly -a FILES=(
  "app.py"
  "user_v1.py"
  "payment_v1.py"
  "admin_user_controls.py"
  "agent_referral.py"
  "admin_web/app.js"
  "admin_web/index.html"
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

if [[ "${EUID}" -ne 0 ]]; then
  fail "请使用 sudo bash 运行此脚本。"
fi
if [[ "$#" -gt 1 ]]; then
  fail "用法：$0 [包含 VIP 支付功能的 40 位提交 SHA]"
fi

if [[ "$#" -eq 1 ]]; then
  [[ "$1" =~ ^[0-9a-fA-F]{40}$ ]] || fail "提交 SHA 必须为完整的 40 位 Git SHA。"
  COMMIT="${1,,}"
else
  echo "正在读取 GitHub ${BRANCH} 最新提交..."
  COMMIT="$(git ls-remote "${REPOSITORY}" "refs/heads/${BRANCH}" | awk 'NR == 1 {print $1}')"
  [[ "${COMMIT}" =~ ^[0-9a-f]{40}$ ]] || fail "无法从 GitHub 读取 ${BRANCH}。"
fi
readonly COMMIT

[[ -d "${APP_DIR}" ]] || fail "未找到后端目录：${APP_DIR}"
[[ -x "${APP_DIR}/.venv/bin/python" ]] || fail "未找到后端 Python：${APP_DIR}/.venv/bin/python"
[[ -d "${APP_DIR}/admin_web" && ! -L "${APP_DIR}/admin_web" ]] || fail "admin_web 必须是实际目录，不能是符号链接。"
id paklicense >/dev/null 2>&1 || fail "系统用户 paklicense 不存在。"
systemctl cat "${SERVICE}" >/dev/null 2>&1 || fail "未找到 systemd 服务：${SERVICE}"
for file in "${FILES[@]}"; do
  target="${APP_DIR}/${file}"
  [[ -f "${target}" && ! -L "${target}" ]] || fail "目标文件不存在或是符号链接：${target}"
done

work_dir="$(mktemp -d /tmp/rylux-vip-payments.XXXXXXXX)"
backup_dir=""
deployment_started=0
rolling_back=0
install_tmp_files=()

cleanup() {
  local file
  for file in "${install_tmp_files[@]}"; do rm -f -- "${file}"; done
  rm -rf -- "${work_dir}"
}

health_check() {
  local health_file="$1" plans_file="$2" asset_file="$3" code=""
  local health_ok=0
  for _ in {1..25}; do
    code="$(curl -sS --max-time 3 -o "${health_file}" -w '%{http_code}' "${HEALTH_URL}" || true)"
    if [[ "${code}" == "200" ]] && "${APP_DIR}/.venv/bin/python" - "${health_file}" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        data = json.load(f)
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)
raise SystemExit(0 if data.get("ok") is True and data.get("product") == "RYLUX" and data.get("api") == "v1" else 1)
PY
    then
      health_ok=1
      break
    fi
    sleep 1
  done
  [[ "${health_ok}" -eq 1 ]] || return 1

  curl -fsS --max-time 5 "${PLANS_URL}" -o "${plans_file}" || return 1
  "${APP_DIR}/.venv/bin/python" - "${plans_file}" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        data = json.load(f)
    plans = {p["code"]: (p["days"], p["amount_fen"]) for p in data["plans"]}
except (OSError, KeyError, TypeError, json.JSONDecodeError):
    raise SystemExit(1)
expected = {"30d": (30, 2000), "90d": (90, 4800), "180d": (180, 7800), "365d": (365, 11800)}
raise SystemExit(0 if all(plans.get(k) == v for k, v in expected.items()) else 1)
PY

  curl -fsS --max-time 5 "${ADMIN_ASSET_URL}" -o "${asset_file}" || return 1
  grep -q 'savePaymentSettings' "${asset_file}"
}

basic_health_check() {
  local health_file="$1" code=""
  for _ in {1..25}; do
    code="$(curl -sS --max-time 3 -o "${health_file}" -w '%{http_code}' "${HEALTH_URL}" || true)"
    if [[ "${code}" == "200" ]] && "${APP_DIR}/.venv/bin/python" - "${health_file}" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        data = json.load(f)
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)
raise SystemExit(0 if data.get("ok") is True and data.get("product") == "RYLUX" and data.get("api") == "v1" else 1)
PY
    then
      return 0
    fi
    sleep 1
  done
  return 1
}

atomic_restore() {
  local source="$1" destination="$2" temp="${destination}.rollback.$$"
  install -o paklicense -g paklicense -m 0644 -- "${source}" "${temp}"
  mv -f -- "${temp}" "${destination}"
}

rollback() {
  local failed=0 file
  [[ "${deployment_started}" -eq 1 && -n "${backup_dir}" ]] || return 0
  [[ "${rolling_back}" -eq 0 ]] || return 1
  rolling_back=1
  echo "部署未通过验收，正在恢复原有后端文件..." >&2
  for file in "${FILES[@]}"; do
    if ! atomic_restore "${backup_dir}/${file}" "${APP_DIR}/${file}"; then
      echo "恢复失败：${APP_DIR}/${file}" >&2
      failed=1
    fi
  done
  if systemctl restart "${SERVICE}" && basic_health_check "${work_dir}/rollback-health.json"; then
    echo "已恢复旧版本，健康检查通过。" >&2
  else
    echo "旧文件已恢复，但服务验收未通过。请检查：systemctl status ${SERVICE}" >&2
    failed=1
  fi
  return "${failed}"
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ "${status}" -ne 0 && "${deployment_started}" -eq 1 ]]; then rollback || true; fi
  cleanup
  exit "${status}"
}
trap on_exit EXIT

echo "[1/6] 获取固定提交 ${COMMIT}..."
git -C "${work_dir}" init --quiet
git -C "${work_dir}" fetch --quiet --depth=1 --no-tags "${REPOSITORY}" "${COMMIT}"
fetched_commit="$(git -C "${work_dir}" rev-parse FETCH_HEAD^{commit})"
[[ "${fetched_commit}" == "${COMMIT}" ]] || fail "取回的提交 SHA 与指定版本不一致。"
for file in "${FILES[@]}"; do
  expected_blob="$(git -C "${work_dir}" rev-parse "${COMMIT}:backend/${file}")"
  mkdir -p -- "${work_dir}/source/$(dirname -- "${file}")"
  git -C "${work_dir}" show "${COMMIT}:backend/${file}" > "${work_dir}/source/${file}"
  actual_blob="$(git -C "${work_dir}" hash-object "${work_dir}/source/${file}")"
  [[ "${actual_blob}" == "${expected_blob}" ]] || fail "下载文件与提交内容不匹配：backend/${file}"
done

echo "[2/6] 检查后端 Python 语法与支付加密依赖..."
"${APP_DIR}/.venv/bin/python" -m py_compile \
  "${work_dir}/source/app.py" "${work_dir}/source/user_v1.py" \
  "${work_dir}/source/payment_v1.py" "${work_dir}/source/admin_user_controls.py" \
  "${work_dir}/source/agent_referral.py"
"${APP_DIR}/.venv/bin/python" -c 'import cryptography'

echo "[3/6] 备份将覆盖的 7 个文件..."
install -d -o root -g root -m 0700 "${BACKUP_ROOT}"
backup_dir="$(mktemp -d "${BACKUP_ROOT}/${COMMIT}.XXXXXXXX")"
for file in "${FILES[@]}"; do install -D -o root -g root -m 0600 -- "${APP_DIR}/${file}" "${backup_dir}/${file}"; done
chown -R root:root "${backup_dir}"
chmod 0700 "${backup_dir}"

echo "[4/6] 安装后端 API 与后台页面..."
deployment_started=1
for file in "${FILES[@]}"; do
  target="${APP_DIR}/${file}"
  temp="${target}.new.$$"
  install_tmp_files+=("${temp}")
  install -o paklicense -g paklicense -m 0644 -- "${work_dir}/source/${file}" "${temp}"
  mv -f -- "${temp}" "${target}"
done

echo "[5/6] 重启 ${SERVICE}..."
systemctl restart "${SERVICE}"

echo "[6/6] 验收健康状态、套餐接口和支付配置页面资源..."
health_check "${work_dir}/health.json" "${work_dir}/plans.json" "${work_dir}/admin.js" || {
  systemctl --no-pager --full status "${SERVICE}" || true
  fail "部署验收未通过。"
}

deployment_started=0
echo "后端更新完成。"
echo "提交：${COMMIT}"
echo "备份：${backup_dir}/"
echo "管理后台：打开“支付配置”填写商户资料。"
echo "说明：此脚本不修改 Nginx；如 /api/v1/ 代理规则有限流，请按 PAYMENTS.md 添加两个精确回调 location。"
