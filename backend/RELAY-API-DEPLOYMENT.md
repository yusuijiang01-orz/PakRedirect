# RYLUX Relay API 部署手册

本文用于把 RYLUX 的 relay 凭证 API 部署到现有的 `verify.lovenom.eu.org` 后端。

不需要修改 DNS。不要把任何真实 secret、账号密码或 session token 写入仓库、APK、日志或聊天记录。

## 1. 工作方式

APK 不直接持有长期 relay secret。运行流程是：

```text
用户登录
  -> POST /api/v1/modules/sg_localization/authorize
  -> GET  /api/v1/modules/sg_localization/relay-token
  -> 后端签发短期 relay 凭证
  -> APK 启动 VPN/WebSocket relay
```

relay API 只接受有效的登录 Bearer token，并要求用户体验或 VIP 权限仍然有效。

## 2. Secret 位置

以下位置必须使用同一个 `RYLUX_RELAY_TOKEN` 值：

- Cloudflare Worker Secret；
- GitHub Repository Secret（用于仓库工作流管理）；
- VPS 后端的 `/etc/pakredirect-license/relay.env`。

GitHub Secret 不会自动同步到 VPS。原始 secret 不应由 APK 请求或返回；后端返回的是带有效期的签名凭证。

## 3. 部署后端代码

在 VPS 上执行。当前功能尚未合并到 `main`，因此必须拉取 `feature/rylux-game-relay`：

```bash
set -e

rm -rf /tmp/RYLUX-v1
git clone --depth 1 --branch feature/rylux-game-relay \
  https://github.com/yusuijiang01-orz/PakRedirect.git /tmp/RYLUX-v1

cp /tmp/RYLUX-v1/backend/app.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/admin_v2.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/admin_key_access.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/admin_code_v1.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/user_v1.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/registration_guard_v1.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/manage.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/requirements.txt /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/pakredirect-license.service \
  /etc/systemd/system/pakredirect-license.service
cp /tmp/RYLUX-v1/backend/nginx-pakredirect-license.conf \
  /etc/nginx/sites-available/pakredirect-license

rm -rf /opt/pakredirect-license/admin_web
cp -a /tmp/RYLUX-v1/backend/admin_web /opt/pakredirect-license/

chown -R paklicense:paklicense /opt/pakredirect-license
/opt/pakredirect-license/.venv/bin/pip install \
  -r /opt/pakredirect-license/requirements.txt
```

数据库位于 `/opt/pakredirect-license/data/licenses.db`，上述操作不会删除数据库。

## 4. 配置 relay secret

在 VPS 上交互输入 secret，避免出现在 shell 历史和命令行参数中：

```bash
install -d -m 700 /etc/pakredirect-license
read -r -s -p "RYLUX_RELAY_TOKEN: " TOKEN
echo
printf 'RYLUX_RELAY_TOKEN=%s\n' "$TOKEN" > /tmp/relay.env
unset TOKEN
install -o root -g root -m 600 /tmp/relay.env \
  /etc/pakredirect-license/relay.env
rm -f /tmp/relay.env
```

systemd 会在服务启动时读取该文件。不要把文件复制到仓库目录，也不要把它提交到 Git。

## 5. 重启服务

```bash
systemctl daemon-reload
systemctl restart pakredirect-license
nginx -t
systemctl reload nginx
```

先检查基础健康状态：

```bash
curl -i https://verify.lovenom.eu.org/healthz
```

预期为 HTTP `200`，响应中包含：

```json
{"ok":true,"product":"RYLUX","api":"v1"}
```

## 6. API 验证

### 登录

使用已有账号登录。不要把真实密码写入脚本或发到聊天：

```bash
curl -sS \
  -H 'Content-Type: application/json' \
  -d '{"username":"你的账号","password":"你的密码","device_id":"manual-api-check"}' \
  https://verify.lovenom.eu.org/api/v1/auth/login
```

响应中的 `token` 是临时登录 session token，只保存在本地内存中。

### 检查模块授权

```bash
curl -sS \
  -H 'Authorization: Bearer 登录返回的token' \
  -X POST \
  https://verify.lovenom.eu.org/api/v1/modules/sg_localization/authorize
```

### 获取 relay 凭证

```bash
curl -sS \
  -H 'Authorization: Bearer 登录返回的token' \
  https://verify.lovenom.eu.org/api/v1/modules/sg_localization/relay-token
```

成功响应包含：

```json
{
  "relay_url": "wss://relay.lovenom.eu.org/rylux-game",
  "relay_token": "短期签名凭证"
}
```

验证时不要直接打印凭证。使用 `jq` 时可以只输出存在性：

```bash
... | jq '{relay_url, relay_token_present:(.relay_token != null and .relay_token != "")}'
```

## 7. 常见响应

| 状态码 | 含义 |
| --- | --- |
| `200` | 已成功签发 relay 凭证 |
| `401` | 缺少或失效的 Bearer session token |
| `403` | 账号停用，或体验/VIP 已过期 |
| `404` | 后端仍是旧版本，或模块代码错误 |
| `503` | VPS 未配置 `RYLUX_RELAY_TOKEN` |

## 8. APK 侧行为

正式 APK 安装后，用户只需要：

1. 登录 RYLUX；
2. 点击启动游戏；
3. 同意 Android VPN 权限。

APK 会自动调用授权和 relay-token 接口。游戏退出或 relay 断开后，VPN 服务应停止，游戏恢复直连。

## 9. 安全要求

- 不要把原始 `RYLUX_RELAY_TOKEN` 写进 Java、Gradle、Worker 代码或 APK；
- 不要把登录 session token 和 relay 凭证提交到 Git；
- relay 目标固定为 `103.206.217.41:6664`，不要改成任意目标代理；
- 只有 `/api/v1/modules/sg_localization/relay-token` 需要签发 relay 凭证；
- 部署完成后检查服务日志，确认没有打印 secret 或完整 relay 凭证。
