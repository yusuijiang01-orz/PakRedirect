# PakRedirect 卡密后端

自托管卡密验证与网页管理后台。

- FastAPI + SQLite
- 验证接口：`https://verify.lovenom.eu.org/api/v1/license/verify`
- 网页后台：`https://verify.lovenom.eu.org/admin`
- 卡密只保存 SHA-256 哈希，不保存完整明文
- 管理员密码使用 PBKDF2-SHA256 哈希保存
- 管理会话使用 HMAC 签名、Secure + HttpOnly Cookie，并对写操作做 CSRF 校验
- Nginx 对卡密验证和后台登录分别限流

## 默认后台账号

首次部署或升级到新版后，后台会自动初始化管理员配置，不再需要手工生成哈希或编辑环境变量。

默认登录：

```text
账号：admin
密码：PakRedirect@2026!
```

首次登录成功后会强制进入“修改管理员账号和密码”页面。在完成修改之前，不能创建、禁用、启用或续期卡密。

修改完成后：

- 默认密码立即失效；
- 新管理员账号和密码哈希保存在 SQLite 数据库的 `admin_settings` 表；
- 会话密钥同时轮换，之前的管理 Cookie 自动失效；
- 后续可以在后台右上角点击“修改账号/密码”再次修改，不需要 SSH 命令。

> 默认密码是公开的临时引导密码。部署新版后应立即登录后台完成修改。

## 已有服务器升级

不会删除现有 `/opt/pakredirect-license/data/licenses.db`，原有卡密和登录记录会保留。

```bash
set -e

rm -rf /tmp/PakRedirect-admin
git clone --depth 1 https://github.com/yusuijiang01-orz/PakRedirect.git /tmp/PakRedirect-admin

cp /tmp/PakRedirect-admin/backend/app.py /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/admin_console.py /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/manage.py /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/requirements.txt /opt/pakredirect-license/
cp /tmp/PakRedirect-admin/backend/pakredirect-license.service /etc/systemd/system/pakredirect-license.service
cp /tmp/PakRedirect-admin/backend/nginx-pakredirect-license.conf /etc/nginx/sites-available/pakredirect-license

chown -R paklicense:paklicense /opt/pakredirect-license
/opt/pakredirect-license/.venv/bin/pip install -r /opt/pakredirect-license/requirements.txt

systemctl daemon-reload
systemctl restart pakredirect-license
nginx -t
systemctl reload nginx
```

检查：

```bash
curl -i https://verify.lovenom.eu.org/healthz
```

然后浏览器访问：

```text
https://verify.lovenom.eu.org/admin
```

首次使用默认账号密码登录并立即修改。

旧的 `/etc/pakredirect-license.env` 不再用于管理员登录，可以保留或删除；新版 systemd 服务不会读取它。

## 网页后台功能

后台支持：

- 一键创建 1 / 7 / 30 / 90 / 180 / 360 天卡；
- 批量生成，一次最多 200 张；
- 查看到期时间和当前状态；
- 按完整卡密、最后 6 位、标签或最后登录 IP 搜索；
- 禁用 / 重新启用；
- 续期 1 / 7 / 30 / 90 / 180 / 360 天；
- 查看最后验证时间和最后登录 IP；
- 页面内修改管理员账号和密码。

完整卡密只会在创建成功时显示一次。历史列表只显示最后 6 位提示；如果粘贴完整卡密搜索，服务端会对输入做 SHA-256 后精确匹配。

## 首次从零安装

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
/opt/pakredirect-license/.venv/bin/pip install -r /opt/pakredirect-license/requirements.txt

chown -R paklicense:paklicense /opt/pakredirect-license
chmod 750 /opt/pakredirect-license
chmod 750 /opt/pakredirect-license/data

cp pakredirect-license.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now pakredirect-license
```

Nginx 使用仓库中的：

```text
backend/nginx-pakredirect-license.conf
```

证书路径：

```text
/etc/letsencrypt/live/verify.lovenom.eu.org/fullchain.pem
/etc/letsencrypt/live/verify.lovenom.eu.org/privkey.pem
```

## CLI

CLI 仍保留用于卡密维护，例如：

```bash
cd /opt/pakredirect-license

sudo -u paklicense \
  PAKREDIRECT_LICENSE_DB=/opt/pakredirect-license/data/licenses.db \
  ./.venv/bin/python manage.py create --days 30 --label "测试"
```

网页后台是日常管理的推荐入口。

## Android 客户端链路

客户端登录和点击“开启汉化”时继续验证：

```text
https://verify.lovenom.eu.org/api/v1/license/verify
```

验证通过后，PakRedirect 在本机监听 `127.0.0.1:18480`，游戏读取：

```text
http://127.0.0.1:18480/linkspak.txt
```

并自行下载本地 PAK。游戏使用自身 UID 写入自己的私有数据目录，因此该模式不需要 Root。
