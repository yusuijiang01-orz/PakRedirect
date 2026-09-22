/**
 * RYLUX 国内分发加速 —— Cloudflare Worker 反代
 *
 * 用途：把 GitHub 上公开的 APK / PAK / 清单 / Release 资产，通过你自己的
 *       Cloudflare Worker 域名转发出境，国内用户可直接下载，流量免费。
 *
 * 用法：任意 GitHub 公开地址，前面加上你的 Worker 域名即可：
 *   https://<你的子域名>.workers.dev/https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/updatefs.pak.rpe
 *   https://<你的子域名>.workers.dev/https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json
 *   https://<你的子域名>.workers.dev/https://api.github.com/repos/yusuijiang01-orz/PakRedirect/releases/latest
 *   https://<你的子域名>.workers.dev/https://github.com/yusuijiang01-orz/PakRedirect/releases/download/game-apk/TamGioiPhanTranhMobile-587.apk
 *
 * 特性：
 *   - 透传 Range 头，支持断点续传
 *   - 无 Range 的 GET 自动在 CF 边缘缓存 1 小时（免费版单对象上限 512MB），二次下载不回源
 *   - 仅允许代理 GitHub 系域名，防止被当作开放代理滥用
 *   - 查询参数原样透传（如 ?ref=main、?sha256=xxx）
 */

// 允许回源的主机白名单（其余一律 403）
const ALLOWED_HOSTS = [
  'raw.githubusercontent.com',
  'media.githubusercontent.com',
  'api.github.com',
  'github.com',
  'objects.githubusercontent.com',
];

// 不向上游透传的请求头（CF 注入的链路信息）
const BLOCKED_HEADERS = [
  'cf-connecting-ip',
  'cf-ray',
  'cf-visitor',
  'x-forwarded-for',
  'x-forwarded-proto',
];

addEventListener('fetch', (event) => {
  event.respondWith(handle(event.request));
});

async function handle(request) {
  const url = new URL(request.url);

  // 目标 URL 写在路径上，查询参数原样透传：
  //   https://worker/https://api.github.com/xxx?ref=main
  let rawTarget = url.pathname.replace(/^\/+/, '');
  if (url.search) rawTarget += url.search;
  rawTarget = decodeURIComponent(rawTarget);

  if (!rawTarget) {
    return new Response(
      'RYLUX-CDN Worker 已就绪。\n用法：在任意 GitHub 公开地址前加上本域名，例如：\n' +
      '  /https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json\n',
      { status: 200, headers: { 'content-type': 'text/plain; charset=utf-8' } }
    );
  }

  let target;
  try {
    target = new URL(rawTarget);
  } catch (e) {
    return jsonResponse('invalid upstream url', 400);
  }
  if (target.protocol !== 'https:' && target.protocol !== 'http:') {
    return jsonResponse('protocol not allowed', 400);
  }
  if (!ALLOWED_HOSTS.includes(target.hostname)) {
    return jsonResponse('host not allowed: ' + target.hostname, 403);
  }

  const method = request.method;
  if (method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
        'Access-Control-Allow-Headers': 'Range, Accept, User-Agent, Content-Type',
        'Access-Control-Max-Age': '86400',
      },
    });
  }

  const headers = new Headers(request.headers);
  headers.set('User-Agent', 'RYLUX-CDN/1.0 (Cloudflare Worker)');
  for (const name of BLOCKED_HEADERS) headers.delete(name);

  const hasRange = headers.has('range');
  const init = { method, headers };
  // 无 Range 的 GET 请求交给 CF 边缘缓存（免费版单对象上限 512MB）
  if (method === 'GET' && !hasRange) {
    init.cf = {
      cacheEverything: true,
      cacheTtl: 3600,
      cacheTtlByStatus: { '200-299': 3600, '301-302': 3600, '404': 1, '500-599': 1 },
    };
  }

  const upstream = await fetch(target.href, init);
  const response = new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: upstream.headers,
  });
  response.headers.set('Access-Control-Allow-Origin', '*');
  response.headers.set('Access-Control-Allow-Headers', 'Range, Accept, User-Agent, Content-Type');
  response.headers.set(
    'Access-Control-Expose-Headers',
    'Content-Length, Content-Range, Accept-Ranges, ETag'
  );
  response.headers.set('X-RYLUX-CDN', 'cf-worker');
  return response;
}

function jsonResponse(message, status) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}
