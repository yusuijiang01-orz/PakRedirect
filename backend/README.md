# PakRedirect 卡密后端

这个目录提供一个最小化的自托管卡密验证后端：

- FastAPI + SQLite
- 卡密服务端只保存 SHA-256 哈希，不保存明文
- 支持创建、停用、重新启用、续期和查看到期时间
- Android 客户端每次登录以及每次点击“开启汉化”都会联网验证
- Nginx 对验证接口做基础限流
- API 监听本机 `127.0.0.1:18888`，公网只暴露 HTTPS

客户端当前验证地址：

`https://38.47.107.59/api/v1/license/verify`

## 1. 安装后端

在 VPS 上执行：

```bash
apt update
apt install -y python3 python3-venv nginx curl ca-certificates
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

## 2. HTTPS：为公网 IP 申请 Let’s Encrypt IP 证书

Let’s Encrypt 已支持公网 IP 证书。IP 证书属于短期证书，必须保持自动续期。

先准备 ACME WebRoot：

```bash
mkdir -p /var/www/certbot
```

安装支持 `--ip-address` 的 Certbot 5.4+。如果系统仓库版本过旧，请升级 Certbot 后再继续。

申请证书：

```bash
certbot certonly \
  --preferred-profile shortlived \
  --webroot \
  --webroot-path /var/www/certbot \
  --ip-address 38.47.107.59
```

然后：

```bash
cp nginx-pakredirect-license.conf /etc/nginx/sites-available/pakredirect-license
ln -sf /etc/nginx/sites-available/pakredirect-license /etc/nginx/sites-enabled/pakredirect-license
nginx -t
systemctl reload nginx
```

确认自动续期：

```bash
systemctl status certbot.timer || true
certbot renew --dry-run
```

验证：

```bash
curl https://38.47.107.59/healthz
```

## 3. 创建卡密

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

续期 30 天：

```bash
sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py extend "PR-XXXX-XXXX-XXXX-XXXX-XXXX" --days 30
```

## 4. 客户端行为

未登录时：

1. 输入卡密。
2. 可勾选“记住卡密”。
3. 成功后进入主界面。

已登录时主界面只显示：

- 卡密到期时间
- `开启汉化` 按钮

点击 `开启汉化` 后：

1. 再次联网验证卡密。
2. 验证通过后取得 APK 内置的所有 `.pak`。
3. Root 覆盖目标目录中同名原文件：
   `/data/data/com.tepaylink.tamgioiphantranhmobile/files/data/`
4. 覆盖成功后启动游戏包：
   `com.tepaylink.tamgioiphantranhmobile`

客户端的“记住卡密”使用 Android Keystore + AES-GCM 保存，不以明文写入 SharedPreferences。

## 安全注意

不要把 VPS root 密码、数据库文件、完整卡密或任何私钥提交到 GitHub。公网验证必须使用 HTTPS，不建议改成明文 HTTP。
