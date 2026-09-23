import { connect } from "cloudflare:sockets";

const ALLOWED_HOSTS = [
  "raw.githubusercontent.com",
  "media.githubusercontent.com",
  "api.github.com",
  "github.com",
  "objects.githubusercontent.com",
];

const BLOCKED_HEADERS = [
  "cf-connecting-ip",
  "cf-ray",
  "cf-visitor",
  "x-forwarded-for",
  "x-forwarded-proto",
];

const GAME_PATH = "/rylux-game";
const GAME_TARGETS = new Map([
  ["/rylux-game", { hostname: "103.206.217.41", port: 6664 }],
  ["/rylux-game/target-2", { hostname: "103.206.217.28", port: 6662 }],
]);

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === GAME_PATH || url.pathname.startsWith(GAME_PATH + "/")) {
      return handleGameRelay(request, env, url.pathname);
    }

    return handleCdnProxy(request);
  },
};

async function handleGameRelay(request, env, path) {
  const target = GAME_TARGETS.get(path);
  if (!target) {
    return jsonResponse("relay target not allowed", 404);
  }

  if (request.method !== "GET") {
    return jsonResponse("method not allowed", 405);
  }

  const token = env.RYLUX_RELAY_TOKEN;
  if (!token) {
    return jsonResponse("relay token is not configured", 503);
  }

  const suppliedToken =
    request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ||
    new URL(request.url).searchParams.get("token");

  if (!suppliedToken || !(suppliedToken === token || await verifyRelayCredential(suppliedToken, token))) {
    return jsonResponse("unauthorized", 401);
  }

  const upgrade = request.headers.get("Upgrade");
  if (!upgrade || upgrade.toLowerCase() !== "websocket") {
    return new Response("WebSocket upgrade required\n", {
      status: 426,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  }

  let socket;
  try {
    socket = connect(target);
    await socket.opened;
  } catch (_) {
    return jsonResponse("target connection failed", 502);
  }

  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];
  server.accept();

  const writer = socket.writable.getWriter();
  let writeChain = Promise.resolve();
  let closed = false;

  const close = async (code = 1000, reason = "") => {
    if (closed) return;
    closed = true;
    try { server.close(code, reason); } catch (_) {}
    try { await writer.close(); } catch (_) {}
    try { await socket.close(); } catch (_) {}
  };

  server.addEventListener("message", (event) => {
    writeChain = writeChain.then(async () => {
      if (closed) return;
      let data = event.data;
      if (typeof data === "string") {
        await close(1003, "binary frames only");
        return;
      }
      if (data instanceof Blob) data = await data.arrayBuffer();
      if (!(data instanceof ArrayBuffer)) {
        await close(1003, "binary frames only");
        return;
      }
      await writer.write(new Uint8Array(data));
    }).catch(() => close(1011, "upstream write failed"));
  });

  server.addEventListener("close", () => close());
  server.addEventListener("error", () => close(1011, "websocket error"));

  (async () => {
    const reader = socket.readable.getReader();
    try {
      while (!closed) {
        const { value, done } = await reader.read();
        if (done) break;
        if (value && value.byteLength) server.send(value);
      }
    } catch (_) {
      // Closing the WebSocket below is enough to notify the client.
    } finally {
      try { reader.releaseLock(); } catch (_) {}
      await close();
    }
  })();

  return new Response(null, { status: 101, webSocket: client });
}

async function handleCdnProxy(request) {
  const url = new URL(request.url);
  let rawTarget = url.pathname.replace(/^\/+/, "");
  if (url.search) rawTarget += url.search;
  rawTarget = decodeURIComponent(rawTarget);
  rawTarget = rawTarget.replace(/^(https?:)\/(?!\/)/, "$1//");

  if (!rawTarget) {
    return new Response(
      "RYLUX-CDN Worker 已就绪。\n" +
      "用法：/https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json\n",
      { status: 200, headers: { "content-type": "text/plain; charset=utf-8" } }
    );
  }

  let target;
  try {
    target = new URL(rawTarget);
  } catch (_) {
    return jsonResponse("invalid upstream url", 400);
  }

  if (target.protocol !== "https:" && target.protocol !== "http:") {
    return jsonResponse("protocol not allowed", 400);
  }
  if (!ALLOWED_HOSTS.includes(target.hostname)) {
    return jsonResponse("host not allowed: " + target.hostname, 403);
  }

  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers": "Range, Accept, User-Agent, Content-Type",
        "Access-Control-Max-Age": "86400",
      },
    });
  }

  const headers = new Headers(request.headers);
  headers.set("User-Agent", "RYLUX-CDN/1.0 (Cloudflare Worker)");
  for (const name of BLOCKED_HEADERS) headers.delete(name);

  const hasRange = headers.has("range");
  const init = { method: request.method, headers };
  if (request.method === "GET" && !hasRange) {
    init.cf = {
      cacheEverything: true,
      cacheTtl: 3600,
      cacheTtlByStatus: {
        "200-299": 3600,
        "301-302": 3600,
        "404": 1,
        "500-599": 1,
      },
    };
  }

  const upstream = await fetch(target.href, init);
  const response = new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: upstream.headers,
  });
  response.headers.set("Access-Control-Allow-Origin", "*");
  response.headers.set("Access-Control-Allow-Headers", "Range, Accept, User-Agent, Content-Type");
  response.headers.set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, ETag");
  response.headers.set("X-RYLUX-CDN", "cf-worker");
  return response;
}

function jsonResponse(message, status) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

async function verifyRelayCredential(value, secret) {
  const parts = value.split(".");
  if (parts.length !== 3 || parts[0] !== "v1" || !parts[1] || !parts[2]) return false;
  try {
    const payload = JSON.parse(new TextDecoder().decode(base64UrlBytes(parts[1])));
    const now = Math.floor(Date.now() / 1000);
    if (payload.v !== 1 || !payload.sub || !Number.isInteger(payload.exp) || payload.exp <= now) {
      return false;
    }
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["verify"],
    );
    return crypto.subtle.verify(
      "HMAC",
      key,
      base64UrlBytes(parts[2]),
      new TextEncoder().encode("v1." + parts[1]),
    );
  } catch (_) {
    return false;
  }
}

function base64UrlBytes(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/")
    + "=".repeat((4 - (value.length % 4)) % 4);
  const raw = atob(padded);
  return Uint8Array.from(raw, (character) => character.charCodeAt(0));
}
