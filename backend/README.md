# PakRedirect 卡密后端

这个目录提供一个最小化的自托管卡密验证后端：

- FastAPI + SQLite
- 卡密服务端只保存 SHA-256 哈希，不保存明文
- 支持创建、停用、重新启用、续期和查看到期时间
- Android 客户端每次登录以及每次点击“开启汉化”都会联网验证
- Nginx 对验证接口做基础限流
- API 监听本机 `127.0.0.1:18888`，公网只暴露 HTTPS

客户端当前验证地址：

`https://verify.lovenom.eu.org/api/v1/license/verify`

## 1. DNS 前置条件

先确保域名：

`verify.lovenom.eu.org`

的 A 记录指向 VPS：

`38.47.107.59`

并确保公网 TCP 80、443 端口可访问。Let's Encrypt HTTP-01 验证需要 80 端口。

## 2. 安装后端

在 VPS 上执行：

```bash
apt update
apt install -y python3 python3-venv nginx curl ca-certificates certbot
useradd --system --home /opt/pakredirect-license --shell /usr/sbin/nologin paklicense 2>/dev/null || true

mkdir -p /opt/pakredirect-license/data
cp app.py manage.py requirements.txt /opt/pakredirect-license/

python3 -m venv /opt/pakredirect-license/.venv
/opt/pakredirect-license/.venv/bin/pip install --upgrade pip
/opt/pakredirect-license/.venv/bin/pip install -r /opt/pakredirect-license/requirements.txt

chown -R paklicense:paklicense /opt/pakredirect-license
chmod 750 /opt/pakredirect-license
chmod 750 /opt/pakredirect-license/data

cp pakredirect-license.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now pakredirect-license
curl http://127.0.0.1:18888/healthz
```

应返回：

```json
{"ok":true}
```

## 3. 为 verify.lovenom.eu.org 申请 Let's Encrypt SSL 证书

先准备 ACME WebRoot：

```bash
mkdir -p /var/www/certbot
```

证书尚未存在时，不要直接启用正式 HTTPS 配置，因为 Nginx 会找不到证书文件。先创建临时 HTTP 配置：

```bash
cat >/etc/nginx/sites-available/pakredirect-license-bootstrap <<'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name verify.lovenom.eu.org;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 404;
    }
}
EOF

ln -sf /etc/nginx/sites-available/pakredirect-license-bootstrap \
  /etc/nginx/sites-enabled/pakredirect-license-bootstrap
nginx -t
systemctl reload nginx
```

确认域名已经能访问到这台 VPS 后申请证书：

```bash
certbot certonly \
  --webroot \
  --webroot-path /var/www/certbot \
  -d verify.lovenom.eu.org
```

申请成功后，证书应位于：

```text
/etc/letsencrypt/live/verify.lovenom.eu.org/fullchain.pem
/etc/letsencrypt/live/verify.lovenom.eu.org/privkey.pem
```

然后切换到仓库内的正式 HTTPS 配置：

```bash
rm -f /etc/nginx/sites-enabled/pakredirect-license-bootstrap
cp nginx-pakredirect-license.conf /etc/nginx/sites-available/pakredirect-license
ln -sf /etc/nginx/sites-available/pakredirect-license \
  /etc/nginx/sites-enabled/pakredirect-license
nginx -t
systemctl reload nginx
```

确认自动续期：

```bash
systemctl enable --now certbot.timer 2>/dev/null || true
systemctl status certbot.timer || true
certbot renew --dry-run
```

验证 HTTPS：

```bash
curl -i https://verify.lovenom.eu.org/healthz
```

应看到 HTTP 200 和：

```json
{"ok":true}
```

再验证卡密接口连通性：

```bash
curl -i \
  -H 'Content-Type: application/json' \
  -d '{"license_key":"INVALID-TEST-KEY"}' \
  https://verify.lovenom.eu.org/api/v1/license/verify
```

只要 TLS 正常、接口返回 JSON，即说明 Android 客户端能够连接验证服务。

## 4. 创建卡密

进入后端目录：

```bash
cd /opt/pakredirect-license
```

创建 30 天卡密：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py create --days 30 --label "测试"
```

创建后会显示完整卡密和到期时间。完整卡密只显示这一次。

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

续期 30 天：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py extend "PR-XXXX-XXXX-XXXX-XXXX-XXXX" --days 30
```

## 5. 客户端行为

未登录时：

1. 输入卡密。
2. 可勾选“记住卡密”。
3. 成功后进入主界面。

已登录时主界面只显示：

- 卡密到期时间
- `开启汉化` 按钮

点击 `开启汉化` 后：

1. 再次通过 `https://verify.lovenom.eu.org` 联网验证卡密。
2. 验证通过后读取 APK 内置的所有 `.pak`。
3. 先关闭目标游戏进程。
4. 使用 Root 覆盖目标目录中同名的原始 PAK 文件：
   `/data/data/com.tepaylink.tamgioiphantranhmobile/files/data/`
5. 校验复制前后的文件大小。
6. 覆盖成功后启动游戏包：
   `com.tepaylink.tamgioiphantranhmobile`

客户端的“记住卡密”使用 Android Keystore + AES-GCM 保存，不以明文写入 SharedPreferences。

## 安全注意

不要把 VPS root 密码、数据库文件、完整卡密或任何私钥提交到 GitHub。Let's Encrypt 私钥只保存在 VPS 的 `/etc/letsencrypt/` 中。公网验证必须使用 HTTPS。
