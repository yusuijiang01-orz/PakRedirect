# PakRedirect 卡密后端

自托管卡密验证与管理后端。

- FastAPI + SQLite
- 公网只暴露 HTTPS
- Android 客户端验证地址：`https://verify.lovenom.eu.org/api/v1/license/verify`
- 网页管理后台：`https://verify.lovenom.eu.org/admin`
- 卡密数据库只保存 SHA-256 哈希，不保存完整明文
- 管理后台支持 1 / 7 / 30 / 90 / 180 / 360 天卡
- 支持批量生成、搜索、禁用、启用、续期、查看到期时间和最后验证 IP
- 管理员密码使用 PBKDF2-SHA256 哈希保存
- 管理会话使用 HMAC 签名、Secure + HttpOnly Cookie，并对写操作做 CSRF 校验

> 完整卡密只会在创建成功时显示一次。之后后台列表只显示最后 6 位提示。
> 搜索时可以粘贴完整卡密进行精确匹配，也可以按最后 6 位、标签或最后登录 IP 搜索。

## 1. 已有服务器升级到网页后台

在 VPS 上执行：

```bash
set -e

rm -rf /tmp/PakRedirect-admin
git clone --depth 1 https://github.com/yusuijiang01-orz/PakRedirect.git /tmp/PakRedirect-admin

cp /tmp/PakRedirect-admin/backend/app.py /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/admin_console.py /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/manage.py /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/requirements.txt /opt/pakredirect-license/

cp /tmp/PakRedirect-admin/backend/pakredirect-license.service \
  /etc/systemd/system/pakredirect-license.service

cp /tmp/PakRedirect-admin/backend/nginx-pakredirect-license.conf \
  /etc/nginx/sites-available/pakredirect-license

chown -R paklicense:paklicense /opt/pakredirect-license

/opt/pakredirect-license/.venv/bin/pip install \
  -r /opt/pakredirect-license/requirements.txt
```

原来的 `/opt/pakredirect-license/data/licenses.db` 不要删除。升级不需要重建数据库。

## 2. 设置管理员账号密码

进入后端目录：

```bash
cd /opt/pakredirect-license
```

生成管理员密码哈希：

```bash
./.venv/bin/python manage.py admin-hash
```

命令会要求输入两次密码，密码至少 10 个字符。输出类似：

```text
pbkdf2_sha256$310000$...$...
```

再生成会话密钥：

```bash
./.venv/bin/python manage.py session-secret
```

将两个输出写入只允许 root 读取的环境文件：

```bash
cat >/etc/pakredirect-license.env <<'EOF'
PAKREDIRECT_ADMIN_USER=admin
PAKREDIRECT_ADMIN_PASSWORD_HASH='把 admin-hash 输出粘贴到这里'
PAKREDIRECT_ADMIN_SESSION_SECRET='把 session-secret 输出粘贴到这里'
EOF

chown root:root /etc/pakredirect-license.env
chmod 600 /etc/pakredirect-license.env
```

如果你希望管理员用户名不是 `admin`，修改 `PAKREDIRECT_ADMIN_USER` 即可。

不要把真实的 `/etc/pakredirect-license.env`、管理员密码哈希、会话密钥、数据库或 TLS 私钥提交到 GitHub。

## 3. 重启服务并启用后台

```bash
systemctl daemon-reload
systemctl restart pakredirect-license

nginx -t
systemctl reload nginx
```

检查：

```bash
curl -i https://verify.lovenom.eu.org/healthz
```

正常返回：

```json
{"ok":true}
```

然后浏览器访问：

```text
https://verify.lovenom.eu.org/admin
```

使用你在 `/etc/pakredirect-license.env` 中设置的管理员账号和密码登录。

## 4. 网页后台功能

### 创建卡密

创建区提供：

- 1 天
- 7 天
- 30 天
- 90 天
- 180 天
- 360 天

默认数量为 `1`。把数量改成大于 1 即为批量生成，一次最多 200 张。

新生成的完整卡密只在本次结果页显示，可点击“复制全部”。

### 搜索

搜索框支持：

- 完整卡密：服务端对输入做 SHA-256 后精确匹配
- 卡密最后 6 位
- 标签
- 最后验证 IP

因为数据库不保存卡密明文，所以历史列表不会重新显示完整卡密。

### 状态与续期

每张卡密可：

- 禁用
- 重新启用
- 续期 1 / 7 / 30 / 90 / 180 / 360 天

如果卡密已经到期，续期从当前时间开始计算；如果尚未到期，则从原到期时间继续增加。

### 登录记录

客户端验证成功后，服务端更新：

- `last_seen_at`
- `last_seen_ip`

网页后台会显示这两个字段。

## 5. CLI 仍然保留

创建 30 天卡密：

```bash
cd /opt/pakredirect-license

sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py create --days 30 --label "测试"
```

查看卡密：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py list
```

停用：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py revoke "PR-XXXX-XXXX-XXXX-XXXX-XXXX"
```

重新启用：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py enable "PR-XXXX-XXXX-XXXX-XXXX-XXXX"
```

续期：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py extend "PR-XXXX-XXXX-XXXX-XXXX-XXXX" --days 30
```

## 6. 首次从零安装

```bash
apt update
apt install -y python3 python3-venv nginx curl ca-certificates certbot git

useradd --system \
  --home /opt/pakredirect-license \
  --shell /usr/sbin/nologin \
  paklicense 2>/dev/null || true

mkdir -p /opt/pakredirect-license/data

cp app.py admin_console.py manage.py requirements.txt /opt/pakredirect-license/

python3 -m venv /opt/pakredirect-license/.venv
/opt/pakredirect-license/.venv/bin/pip install --upgrade pip
/opt/pakredirect-license/.venv/bin/pip install \
  -r /opt/pakredirect-license/requirements.txt

chown -R paklicense:paklicense /opt/pakredirect-license
chmod 750 /opt/pakredirect-license
chmod 750 /opt/pakredirect-license/data

cp pakredirect-license.service /etc/systemd/system/
```

然后按照本文第 2 节配置管理员环境文件，再启动服务：

```bash
systemctl daemon-reload
systemctl enable --now pakredirect-license
curl http://127.0.0.1:18888/healthz
```

## 7. HTTPS / Nginx

域名：

```text
verify.lovenom.eu.org
```

正式 Nginx 配置已经包含：

- `/healthz`
- `/api/v1/license/verify`
- `/admin`
- `/admin/login` 登录限流

证书路径：

```text
/etc/letsencrypt/live/verify.lovenom.eu.org/fullchain.pem
/etc/letsencrypt/live/verify.lovenom.eu.org/privkey.pem
```

已有证书时直接：

```bash
cp nginx-pakredirect-license.conf /etc/nginx/sites-available/pakredirect-license
ln -sf /etc/nginx/sites-available/pakredirect-license \
  /etc/nginx/sites-enabled/pakredirect-license

nginx -t
systemctl reload nginx
```

首次申请证书可使用 WebRoot：

```bash
mkdir -p /var/www/certbot

certbot certonly \
  --webroot \
  --webroot-path /var/www/certbot \
  -d verify.lovenom.eu.org
```

确认续期：

```bash
systemctl enable --now certbot.timer 2>/dev/null || true
certbot renew --dry-run
```

## 8. Android 客户端当前链路

客户端卡密验证仍使用：

```text
https://verify.lovenom.eu.org/api/v1/license/verify
```

点击“开启汉化”后：

1. 再次联网验证卡密。
2. 验证成功后启动 PakRedirect 本地 HTTP 服务。
3. 本机监听 `127.0.0.1:18480`。
4. 游戏读取 `http://127.0.0.1:18480/linkspak.txt`。
5. 清单把内置 PAK 指向 `http://127.0.0.1:18480/pak/<文件名>`。
6. 游戏自行下载，并以游戏自己的 UID 写入其私有数据目录。
7. 该本地直供模式不需要 Root。

## 安全说明

- 公网管理后台只通过 HTTPS 使用。
- 管理员密码不以明文存储在服务配置中，只保存 PBKDF2-SHA256 哈希。
- 管理 Cookie 使用 `Secure`、`HttpOnly`、`SameSite=Lax`。
- 修改卡密状态、创建和续期操作带 CSRF 校验。
- Nginx 对管理员登录入口和卡密验证接口分别限流。
- 卡密数据库继续只保存 SHA-256 哈希，因此服务器数据库泄露时不会直接暴露完整卡密。
