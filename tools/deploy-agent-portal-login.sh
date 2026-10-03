#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly REPOSITORY="https://github.com/yusuijiang01-orz/PakRedirect.git"
readonly BRANCH="main"
readonly APP_DIR="/opt/pakredirect-license"
readonly SERVICE="pakredirect-license.service"
readonly HEALTH_URL="http://127.0.0.1:18888/healthz"
readonly BACKUP_ROOT="/var/backups/pakredirect-license/agent-portal-login"
readonly NGINX_SITE="/etc/nginx/sites-available/pakredirect-license"
readonly -a FILES=("agent_referral.py" "user_v1.py" "agent_web/app.js")

fail() { echo "错误：$*" >&2; exit 1; }

if [[ "${EUID}" -ne 0 ]]; then fail "请使用 sudo bash 运行此脚本。"; fi
if [[ "$#" -gt 1 ]]; then fail "用法：$0 [包含代理登录修复的完整 40 位提交 SHA]"; fi

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
id paklicense >/dev/null 2>&1 || fail "系统用户 paklicense 不存在。"
systemctl cat "${SERVICE}" >/dev/null 2>&1 || fail "未找到 systemd 服务：${SERVICE}"
for file in "${FILES[@]}"; do
  target="${APP_DIR}/${file}"
  [[ -f "${target}" && ! -L "${target}" ]] || fail "目标文件不存在或是符号链接：${target}"
done

[[ -f "${NGINX_SITE}" ]] || fail "未找到 Nginx 站点配置：${NGINX_SITE}"
NGINX_REAL="$(readlink -f -- "${NGINX_SITE}")"
[[ "${NGINX_REAL}" == /etc/nginx/* && -f "${NGINX_REAL}" && ! -L "${NGINX_REAL}" ]] || fail "Nginx 站点配置目标异常：${NGINX_REAL}"
grep -Eq 'server_name[^;]*verify\.lovenom\.eu\.org' "${NGINX_REAL}" || fail "该 Nginx 配置不是 verify.lovenom.eu.org 站点。"
grep -Fq 'zone=ryluxauth:' "${NGINX_REAL}" || fail "Nginx 配置缺少 ryluxauth 频率限制区，未做任何更改。"
grep -Fq 'location ^~ /agent/' "${NGINX_REAL}" || fail "Nginx 配置未转发 /agent/ 路径，未做任何更改。"

work_dir="$(mktemp -d /tmp/rylux-agent-portal.XXXXXXXX)"
backup_dir=""
deployment_started=0
nginx_changed=0
rolling_back=0
install_tmp_files=()

cleanup() {
  local file
  for file in "${install_tmp_files[@]}"; do rm -f -- "${file}"; done
  rm -rf -- "${work_dir}"
}

health_check() {
  local response_file="$1" code=""
  for _ in {1..25}; do
    code="$(curl -sS --max-time 3 -o "${response_file}" -w '%{http_code}' "${HEALTH_URL}" || true)"
    if [[ "${code}" == "200" ]] && "${APP_DIR}/.venv/bin/python" - "${response_file}" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        data = json.load(f)
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)
raise SystemExit(0 if data.get("ok") is True and data.get("product") == "RYLUX" and data.get("api") == "v1" else 1)
PY
    then return 0; fi
    sleep 1
  done
  return 1
}

atomic_restore() {
  local source="$1" destination="$2" owner="$3" group="$4" mode="$5"
  local temp="${destination}.rollback.$$"
  install -o "${owner}" -g "${group}" -m "${mode}" -- "${source}" "${temp}"
  mv -f -- "${temp}" "${destination}"
}

rollback() {
  local failed=0 file
  [[ "${deployment_started}" -eq 1 && -n "${backup_dir}" ]] || return 0
  [[ "${rolling_back}" -eq 0 ]] || return 1
  rolling_back=1
  echo "部署未通过验收，正在恢复原文件..." >&2
  for file in "${FILES[@]}"; do
    if ! atomic_restore "${backup_dir}/${file}" "${APP_DIR}/${file}" paklicense paklicense 0644; then
      echo "恢复失败：${APP_DIR}/${file}" >&2; failed=1
    fi
  done
  if [[ "${nginx_changed}" -eq 1 ]]; then
    if ! atomic_restore "${backup_dir}/nginx-site" "${NGINX_REAL}" root root "$(stat -c '%a' "${NGINX_REAL}")"; then
      echo "恢复 Nginx 配置失败：${NGINX_REAL}" >&2; failed=1
    fi
    nginx -t && systemctl reload nginx || failed=1
  fi
  if systemctl restart "${SERVICE}" && health_check "${work_dir}/rollback-health.json"; then
    echo "旧版本已恢复，后端健康检查通过。" >&2
  else
    echo "旧文件已恢复，但健康检查未通过。请检查：systemctl status ${SERVICE}" >&2
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

echo "[1/6] 下载并校验固定提交 ${COMMIT}..."
git -C "${work_dir}" init --quiet
git -C "${work_dir}" fetch --quiet --depth=1 --no-tags "${REPOSITORY}" "${COMMIT}"
fetched_commit="$(git -C "${work_dir}" rev-parse FETCH_HEAD^{commit})"
[[ "${fetched_commit}" == "${COMMIT}" ]] || fail "取回的提交 SHA 与指定版本不一致。"
for file in "${FILES[@]}"; do
  source_path="backend/${file}"
  expected_blob="$(git -C "${work_dir}" rev-parse "${COMMIT}:${source_path}")"
  mkdir -p -- "${work_dir}/source/$(dirname -- "${file}")"
  git -C "${work_dir}" show "${COMMIT}:${source_path}" > "${work_dir}/source/${file}"
  actual_blob="$(git -C "${work_dir}" hash-object "${work_dir}/source/${file}")"
  [[ "${actual_blob}" == "${expected_blob}" ]] || fail "下载文件与提交内容不匹配：${source_path}"
done

echo "[2/6] 检查 Python 语法并准备 Nginx 登录限流规则..."
"${APP_DIR}/.venv/bin/python" -m py_compile \
  "${work_dir}/source/agent_referral.py" "${work_dir}/source/user_v1.py"
nginx -t -c /etc/nginx/nginx.conf || fail "当前 Nginx 配置检查未通过，未替换后端文件。"
cp -a -- "${NGINX_REAL}" "${work_dir}/nginx-site.new"
if ! grep -Fq 'location = /agent/api/auth/login' "${work_dir}/nginx-site.new"; then
  "${APP_DIR}/.venv/bin/python" - "${work_dir}/nginx-site.new" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
marker = "    location ^~ /agent/ {"
block = (
    "    location = /agent/api/auth/login {\n"
    "        limit_req zone=ryluxauth burst=8 nodelay;\n"
    "        proxy_pass http://127.0.0.1:18888;\n"
    "        proxy_set_header Host $host;\n"
    "        proxy_set_header X-Real-IP $remote_addr;\n"
    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
    "        proxy_set_header X-Forwarded-Proto https;\n"
    "    }\n\n"
)
if text.count(marker) != 1:
    raise SystemExit("未找到唯一的 /agent/ Nginx 代理规则，停止部署。")
path.write_text(text.replace(marker, block + marker, 1), encoding="utf-8")
PY
fi

echo "[3/6] 备份即将更新的后端文件和 Nginx 配置..."
install -d -o root -g root -m 0700 "${BACKUP_ROOT}"
backup_dir="$(mktemp -d "${BACKUP_ROOT}/${COMMIT}.XXXXXXXX")"
for file in "${FILES[@]}"; do install -D -o root -g root -m 0600 -- "${APP_DIR}/${file}" "${backup_dir}/${file}"; done
install -o root -g root -m 0600 -- "${NGINX_REAL}" "${backup_dir}/nginx-site"

echo "[4/6] 原子更新后端文件和 Nginx 登录限流规则..."
deployment_started=1
for file in "${FILES[@]}"; do
  target="${APP_DIR}/${file}"
  temp="${target}.new.$$"
  install_tmp_files+=("${temp}")
  install -o paklicense -g paklicense -m 0644 -- "${work_dir}/source/${file}" "${temp}"
  mv -f -- "${temp}" "${target}"
done
if ! cmp -s -- "${NGINX_REAL}" "${work_dir}/nginx-site.new"; then
  nginx_temp="${NGINX_REAL}.new.$$"
  install_tmp_files+=("${nginx_temp}")
  install -o root -g root -m "$(stat -c '%a' "${NGINX_REAL}")" -- "${work_dir}/nginx-site.new" "${nginx_temp}"
  mv -f -- "${nginx_temp}" "${NGINX_REAL}"
  nginx_changed=1
  nginx -t || fail "Nginx 配置验收失败。"
fi

echo "[5/6] 重启后端并重载 Nginx..."
systemctl restart "${SERVICE}"
if [[ "${nginx_changed}" -eq 1 ]]; then systemctl reload nginx; fi

echo "[6/6] 检查后端健康状态和已部署的代理登录页面..."
health_check "${work_dir}/health.json" || {
  systemctl --no-pager --full status "${SERVICE}" || true
  fail "部署验收未通过。"
}
curl -fsS --max-time 5 "${HEALTH_URL%/healthz}/agent/app.js" -o "${work_dir}/agent-app.js" || fail "无法读取已部署代理页面脚本。"
grep -q '"/agent/api/auth/login"' "${work_dir}/agent-app.js" || fail "代理页面仍未使用新的登录接口。"

deployment_started=0
echo "代理后台更新完成。"
echo "提交：${COMMIT}"
echo "备份：${backup_dir}/"
echo "现在可以重新打开 https://verify.lovenom.eu.org/agent/ 登录。"
