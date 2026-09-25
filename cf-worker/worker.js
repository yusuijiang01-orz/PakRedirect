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
const GAME_UPSTREAM = { hostname: "verify.lovenom.eu.org", port: 9443 };
const GAME_UPSTREAM_TCP_MAGIC = new Uint8Array([82, 89, 76, 85, 88, 72, 89, 50, 1]); // RYLUXHY2 + TCP
const GAME_UPSTREAM_UDP_MAGIC = new Uint8Array([82, 89, 76, 85, 88, 72, 89, 50, 2]); // RYLUXHY2 + UDP
const GAME_UPSTREAM_ACK = new Uint8Array([82, 89, 76, 79, 75, 0]); // RYLOK + status=0
const LEGACY_GAME_TARGETS = new Map([
  [GAME_PATH, { hostname: "103.206.217.28", port: 5622 }],
  [GAME_PATH + "/target-2", { hostname: "103.206.217.28", port: 6662 }],
  [GAME_PATH + "/target-3", { hostname: "103.206.217.28", port: 5622 }],
]);

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === GAME_PATH || url.pathname.startsWith(GAME_PATH + "/")) {
      const target = resolveGameTarget(url.pathname);
      if (!target) return jsonResponse("game target not allowed", 404);
      return handleGameRelay(request, env, target);
    }

    return handleCdnProxy(request);
  },
};

function resolveGameTarget(path) {
  if (path === GAME_PATH + "/udp") return { mode: "udp" };
  const legacy = LEGACY_GAME_TARGETS.get(path);
  if (legacy) return legacy;

  const segments = path.split("/");
  if (segments.length !== 4 || segments[1] !== "rylux-game") return null;
  const octets = segments[2].split(".");
  if (octets.length !== 4 || octets[0] !== "103" || octets[1] !== "206" || octets[2] !== "217") return null;
  const last = Number(octets[3]);
  const port = Number(segments[3]);
  if (String(last) !== octets[3] || last < 0 || last > 255) return null;
  if (String(port) !== segments[3] || port < 1 || port > 65535) return null;
  return { hostname: "103.206.217." + last, port };
}

async function handleGameRelay(request, env, target) {
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
  let upstreamReader;
  let upstreamWriter;
  let initialUpstreamData = new Uint8Array(0);
  try {
    socket = connect(GAME_UPSTREAM, { secureTransport: "on", allowHalfOpen: true });
    await socket.opened;
    upstreamWriter = socket.writable.getWriter();
    await upstreamWriter.write(encodeGameUpstreamHello(target, token));
    upstreamReader = socket.readable.getReader();
    const greeting = await readGameUpstreamAck(upstreamReader);
    if (!greeting) {
      try { await socket.close(); } catch (_) {}
      return jsonResponse("game relay upstream rejected target", 502);
    }
    initialUpstreamData = greeting;
  } catch (_) {
    if (socket) try { await socket.close(); } catch (_) {}
    return jsonResponse("target connection failed", 502);
  }

  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];
  server.accept();

  const writer = upstreamWriter;
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
      const bytes = new Uint8Array(data);
      await writer.write(target.mode === "udp" ? encodeLengthFrame(bytes) : bytes);
    }).catch(() => close(1011, "upstream write failed"));
  });

  server.addEventListener("close", () => close());
  server.addEventListener("error", () => close(1011, "websocket error"));

  (async () => {
    const reader = upstreamReader;
    try {
      if (target.mode === "udp") {
        const framed = new LengthPrefixedReader(reader, initialUpstreamData);
        while (!closed) {
          const packet = await framed.readFrame();
          if (packet === null) break;
          server.send(packet);
        }
      } else {
        if (initialUpstreamData.length) server.send(initialUpstreamData);
        while (!closed) {
          const { value, done } = await reader.read();
          if (done) break;
          if (value && value.byteLength) server.send(value);
        }
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

function encodeGameUpstreamHello(target, token) {
  const tokenBytes = new TextEncoder().encode(token);
  if (!tokenBytes.length || tokenBytes.length > 65535) throw new Error("invalid relay credential");
  const isUdp = target.mode === "udp";
  const magic = isUdp ? GAME_UPSTREAM_UDP_MAGIC : GAME_UPSTREAM_TCP_MAGIC;
  const octets = isUdp ? [] : target.hostname.split(".").map(Number);
  if (!isUdp && (octets.length !== 4 || octets.some((part) => !Number.isInteger(part) || part < 0 || part > 255))) {
    throw new Error("invalid game target address");
  }
  const bytes = new Uint8Array(magic.length + 2 + tokenBytes.length + (isUdp ? 0 : 6));
  let offset = 0;
  bytes.set(magic, offset); offset += magic.length;
  bytes[offset++] = (tokenBytes.length >>> 8) & 0xff;
  bytes[offset++] = tokenBytes.length & 0xff;
  bytes.set(tokenBytes, offset); offset += tokenBytes.length;
  if (!isUdp) {
    bytes.set(octets, offset); offset += 4;
    bytes[offset++] = (target.port >>> 8) & 0xff;
    bytes[offset] = target.port & 0xff;
  }
  return bytes;
}

function encodeLengthFrame(data) {
  if (data.length < 1 || data.length > 65535) throw new Error("invalid UDP packet size");
  const frame = new Uint8Array(data.length + 2);
  frame[0] = (data.length >>> 8) & 0xff;
  frame[1] = data.length & 0xff;
  frame.set(data, 2);
  return frame;
}

class LengthPrefixedReader {
  constructor(reader, initial = new Uint8Array(0)) {
    this.reader = reader;
    this.buffer = initial;
  }

  async readBytes(length) {
    while (this.buffer.length < length) {
      const { value, done } = await this.reader.read();
      if (done) return null;
      const chunk = value || new Uint8Array(0);
      const joined = new Uint8Array(this.buffer.length + chunk.length);
      joined.set(this.buffer);
      joined.set(chunk, this.buffer.length);
      this.buffer = joined;
    }
    const result = this.buffer.slice(0, length);
    this.buffer = this.buffer.slice(length);
    return result;
  }

  async readFrame() {
    const header = await this.readBytes(2);
    if (header === null) return null;
    const length = (header[0] << 8) | header[1];
    if (length < 1) throw new Error("invalid upstream UDP frame");
    const frame = await this.readBytes(length);
    if (frame === null) throw new Error("truncated upstream UDP frame");
    return frame;
  }
}

function startsWithBytes(value, prefix) {
  if (value.length < prefix.length) return false;
  for (let i = 0; i < prefix.length; i++) if (value[i] !== prefix[i]) return false;
  return true;
}

async function readGameUpstreamAck(reader) {
  let buffered = new Uint8Array(0);
  while (buffered.length < GAME_UPSTREAM_ACK.length) {
    const { value, done } = await reader.read();
    if (done || !value) return null;
    const joined = new Uint8Array(buffered.length + value.length);
    joined.set(buffered);
    joined.set(value, buffered.length);
    buffered = joined;
  }
  if (!startsWithBytes(buffered, GAME_UPSTREAM_ACK)) return null;
  return buffered.slice(GAME_UPSTREAM_ACK.length);
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

