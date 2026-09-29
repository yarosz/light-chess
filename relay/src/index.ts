// The Relay's Worker: routes docs/protocol.md onto one CorrespondenceGame Durable Object per Game.
// It hashes every secret before handing it on, so a Durable Object never sees a seat secret or an
// open invite in the clear. (A rematchOffer's join token is the exception: it is a Game Event, kept
// in the old Game's log so the other Seat can read it.) The creator's seat secret is made here; the
// joining Seat's is the phone's own, sent with the redeem or join, so a retry is idempotent (W9).

import { CorrespondenceGame, respond } from "./game";
import {
  GAME_ID,
  MAJORS,
  MAX_BODY_BYTES,
  PROTOCOL,
  ProtocolError,
  fail,
  parseAppend,
  parseCreate,
  parseSeatRequest,
  parseSince,
  parseSync,
  type Reply,
} from "./protocol";
import { clientKey, isRefusedPlainHttp } from "./limits";
import { displayInviteCode, newInviteCode, newToken, normalizeInviteCode, sha256Hex } from "./secrets";

export { CorrespondenceGame };

/** How many fresh Invite Codes to try before giving up on a collision (40-bit codes: never in practice). */
const CODE_ATTEMPTS = 5;

type Handler = (request: Request, env: Env, major: number, params: string[]) => Promise<Response>;

const ROUTES: { pattern: RegExp; methods: Record<string, Handler> }[] = [
  { pattern: /^\/games$/, methods: { POST: createGame } },
  { pattern: /^\/invites\/([^/]+)\/redeem$/, methods: { POST: redeemCode } },
  { pattern: /^\/games\/([^/]+)\/join$/, methods: { POST: redeemToken } },
  { pattern: /^\/games\/([^/]+)\/invite$/, methods: { DELETE: cancelInvite } },
  { pattern: /^\/games\/([^/]+)\/events$/, methods: { GET: readEvents, POST: appendEvent } },
  { pattern: /^\/games\/([^/]+)\/live$/, methods: { GET: live } },
  { pattern: /^\/sync$/, methods: { POST: sync } },
];

export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof ProtocolError) return respond(e.reply);
      throw e;
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  // Refused, never redirected: a client that sent a secret in the clear has already sent it, and a
  // redirected POST can come back as a GET (L3).
  if (isRefusedPlainHttp(url)) {
    return respond(fail("upgrade_required", `Use HTTPS: https://${url.host}${url.pathname}`));
  }
  const { pathname } = url;
  if (pathname === "/health") {
    if (request.method !== "GET") return respond(fail("method_not_allowed", "Use GET"));
    return Response.json({ status: "ok", protocol: PROTOCOL, majors: MAJORS });
  }
  const versioned = /^\/v(\d+)(\/.*)$/.exec(pathname);
  if (!versioned) return respond(fail("not_found", `No endpoint ${pathname}`));
  const major = Number(versioned[1]);
  if (!MAJORS.includes(major)) {
    return respond(
      fail("unsupported_version", `This Relay serves majors ${MAJORS.join(", ")}; got ${major}`, { majors: [...MAJORS] }),
    );
  }
  const rest = versioned[2]!;
  for (const { pattern, methods } of ROUTES) {
    const match = pattern.exec(rest);
    if (!match) continue;
    const handler = methods[request.method];
    if (!handler) return respond(fail("method_not_allowed", `Use ${Object.keys(methods).join(" or ")}`));
    let params: string[];
    try {
      params = match.slice(1).map(decodeURIComponent);
    } catch {
      return respond(fail("bad_request", "The path has a malformed percent-escape"));
    }
    return handler(request, env, major, params);
  }
  return respond(fail("not_found", `No endpoint ${pathname}`));
}

/**
 * The JSON body, at most MAX_BODY_BYTES (R5). Content-Length is checked first, and the bytes are
 * counted as they are read, because a chunked body has no Content-Length.
 */
async function body(request: Request): Promise<unknown> {
  const tooLarge = () => new ProtocolError(fail("body_too_large", `A request body is at most ${MAX_BODY_BYTES} bytes`));
  const declared = Number(request.headers.get("Content-Length") ?? 0);
  if (declared > MAX_BODY_BYTES) throw tooLarge();
  const chunks: Uint8Array[] = [];
  let size = 0;
  if (request.body) {
    const reader = request.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        throw tooLarge();
      }
      chunks.push(value);
    }
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new ProtocolError(fail("bad_request", "The body must be JSON"));
  }
}

function gameStub(env: Env, gameId: string): DurableObjectStub<CorrespondenceGame> {
  const notFound = new ProtocolError(fail("game_not_found", "No such Game"));
  if (!GAME_ID.test(gameId)) throw notFound;
  try {
    return env.GAME.get(env.GAME.idFromString(gameId));
  } catch {
    throw notFound;
  }
}

async function bearerHash(request: Request): Promise<string> {
  const secret = /^Bearer (\S+)$/.exec(request.headers.get("Authorization") ?? "")?.[1];
  if (!secret) throw new ProtocolError(fail("bad_seat_secret", "Send the seat secret as Authorization: Bearer <secret>"));
  return sha256Hex(secret);
}

/** Adds the secrets (known only here, never stored) to a successful Durable Object reply. */
function withSecrets(reply: Reply, secrets: Record<string, string>): Response {
  if (reply.status >= 300) return respond(reply);
  return respond({ status: reply.status, body: { ...(reply.body as object), ...secrets } });
}

/**
 * Counts one request against [limiter] under its client's key (L1). Null when it may go on, else
 * the `429 rate_limited` reply, with Retry-After set to the limiters' 60-second period.
 */
async function limited(request: Request, limiter: RateLimit, what: string): Promise<Response | null> {
  const { success } = await limiter.limit({ key: clientKey(request.headers.get("CF-Connecting-IP")) });
  if (success) return null;
  return respond(fail("rate_limited", `Too many ${what}; wait a minute`), { "Retry-After": "60" });
}

async function createGame(request: Request, env: Env, major: number): Promise<Response> {
  // Counted before the body is read, like a redemption (L2).
  const refused = await limited(request, env.CREATE_LIMITER, "Games created");
  if (refused) return refused;
  const req = parseCreate(await body(request), major);
  const seatSecret = newToken();
  const seatHash = await sha256Hex(seatSecret);
  if (req.invite === "token") {
    const joinToken = newToken();
    const stub = env.GAME.get(env.GAME.newUniqueId());
    const reply = await stub.create(req, seatHash, await sha256Hex(joinToken));
    return withSecrets(reply!, { seatSecret, joinToken });
  }
  for (let attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
    const code = newInviteCode();
    const stub = env.GAME.getByName(`invite:${code}`);
    const reply = await stub.create(req, seatHash, await sha256Hex(code));
    if (reply) return withSecrets(reply, { seatSecret, inviteCode: displayInviteCode(code) });
  }
  throw new Error("no free Invite Code after several attempts");
}

async function redeemCode(request: Request, env: Env, major: number, [rawCode]: string[]): Promise<Response> {
  // Counted before the code is read, so malformed guesses cost the same as wrong ones (F11).
  const refused = await limited(request, env.REDEEM_LIMITER, "invite redemptions");
  if (refused) return refused;
  const req = parseSeatRequest(await body(request), major, false);
  const code = normalizeInviteCode(rawCode!);
  if (!code) return respond(fail("bad_request", "An Invite Code is 8 characters, such as ABCD-EFGH"));
  const stub = env.GAME.getByName(`invite:${code}`);
  return respond(await stub.redeem(req.v, await sha256Hex(code), await sha256Hex(req.seatSecret)));
}

async function redeemToken(request: Request, env: Env, major: number, [gameId]: string[]): Promise<Response> {
  const req = parseSeatRequest(await body(request), major, true);
  const stub = gameStub(env, gameId!);
  return respond(await stub.redeem(req.v, await sha256Hex(req.joinToken!), await sha256Hex(req.seatSecret)));
}

async function cancelInvite(request: Request, env: Env, _major: number, [gameId]: string[]): Promise<Response> {
  const secretHash = await bearerHash(request);
  return respond(await gameStub(env, gameId!).cancel(secretHash));
}

async function readEvents(request: Request, env: Env, _major: number, [gameId]: string[]): Promise<Response> {
  const since = parseSince(new URL(request.url).searchParams.get("since"));
  const secretHash = await bearerHash(request);
  return respond(await gameStub(env, gameId!).read(secretHash, since));
}

async function appendEvent(request: Request, env: Env, major: number, [gameId]: string[]): Promise<Response> {
  const secretHash = await bearerHash(request);
  const entry = parseAppend(await body(request), major);
  return respond(await gameStub(env, gameId!).append(secretHash, entry));
}

async function live(request: Request, env: Env, _major: number, [gameId]: string[]): Promise<Response> {
  if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
    return respond(fail("upgrade_required", "This endpoint is a WebSocket"));
  }
  return gameStub(env, gameId!).fetch(request);
}

async function sync(request: Request, env: Env, major: number): Promise<Response> {
  const items = parseSync(await body(request), major);
  const results = await Promise.all(
    items.map(async ({ gameId, seatSecret, since }) => {
      let reply: Reply;
      try {
        reply = await gameStub(env, gameId).read(await sha256Hex(seatSecret), since);
      } catch (e) {
        if (!(e instanceof ProtocolError)) throw e;
        reply = e.reply;
      }
      return reply.status === 200
        ? { gameId, ok: true, game: reply.body }
        : { gameId, ok: false, ...(reply.body as object) };
    }),
  );
  return Response.json({ results });
}
