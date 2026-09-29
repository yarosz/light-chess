import { SELF, env, runInDurableObject } from "cloudflare:test";
import type { Side } from "../src/protocol";
import { newToken } from "../src/secrets";

export const ORIGIN = "https://relay.test";

export interface Res<T = any> {
  status: number;
  headers: Headers;
  json: T;
}

export async function call<T = any>(
  method: string,
  path: string,
  opts: { body?: unknown; secret?: string; ip?: string; headers?: Record<string, string> } = {},
): Promise<Res<T>> {
  const headers: Record<string, string> = { ...opts.headers };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.secret) headers.Authorization = `Bearer ${opts.secret}`;
  headers["CF-Connecting-IP"] = opts.ip ?? "192.0.2.1";
  const res = await SELF.fetch(ORIGIN + path, {
    method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  const text = await res.text();
  return { status: res.status, headers: res.headers, json: text ? JSON.parse(text) : null };
}

/** A stand-in digest: the Relay checks only that it is 64 lowercase hex characters. */
export const digest = (n: number): string => n.toString(16).padStart(64, "0");

// Each test file gets its own module instance and its own Miniflare rate-limit state (checked with
// two files spending the same key), so these counters only need to be distinct within a file.

let ipCounter = 0;
/**
 * A fresh IPv4 address in the benchmarking range 198.18.0.0/15 (131,071 of them), so one test's
 * requests never touch another's rate limit (L1, L2).
 */
export const freshIp = (): string => {
  const n = ++ipCounter;
  return `198.${18 + (n >> 16)}.${(n >> 8) & 255}.${n & 255}`;
};

let blockCounter = 0;
/**
 * A fresh IPv6 /48 in the documentation range 2001:db8::/32, as its first three hextets
 * (`2001:db8:1a`), so one test's addresses never touch another's /64 or /48 limits (L1, L5).
 */
export const fresh48 = (): string => `2001:db8:${(++blockCounter).toString(16)}`;

/** An address in a fresh IPv6 /48 (and so a fresh /64). */
export const freshV6 = (): string => `${fresh48()}::1`;

/**
 * Waits out the last 10 seconds of a clock minute. Miniflare's limiter counts in fixed 60-second
 * windows aligned to the clock, so a test that spends a budget and then expects a 429 would find
 * the budget reset if a new window opened midway.
 */
export async function oneWindow(): Promise<void> {
  const into = Date.now() % 60_000;
  if (into > 50_000) await new Promise((resolve) => setTimeout(resolve, 60_000 - into + 100));
}

export interface Created {
  gameId: string;
  seatSecret: string;
  inviteCode?: string;
  joinToken?: string;
  side: Side;
  inviteExpiresAt: number;
}

export async function create(
  side: Side = "white",
  extra: { invite?: "code" | "token"; daysPerMove?: number; v?: string } = {},
): Promise<Created> {
  const res = await call("POST", "/v1/games", {
    ip: freshV6(),
    body: { v: extra.v ?? "1.0", side, daysPerMove: extra.daysPerMove ?? 3, invite: extra.invite ?? "code" },
  });
  if (res.status !== 201) throw new Error(`create failed: ${JSON.stringify(res.json)}`);
  return res.json;
}

/**
 * Takes the other Seat with the phone's own seat secret (W9): [secret] defaults to a fresh one. On
 * success the secret is added to the reply as `seatSecret`, the way the phone keeps it, so tests can
 * act as that Seat.
 */
export async function redeem(code: string, v = "1.0", secret: string = newToken(), ip: string = freshIp()): Promise<Res> {
  return seated(await call("POST", `/v1/invites/${encodeURIComponent(code)}/redeem`, { body: { v, seatSecret: secret }, ip }), secret);
}

export async function join(gameId: string, joinToken: string, secret: string = newToken()): Promise<Res> {
  return seated(await call("POST", `/v1/games/${gameId}/join`, { body: { v: "1.0", joinToken, seatSecret: secret } }), secret);
}

function seated(res: Res, secret: string): Res {
  if (res.status === 200) {
    // The Relay never hands the secret back: the phone chose it.
    if ("seatSecret" in res.json) throw new Error("the Relay echoed a seat secret");
    res.json = { ...res.json, seatSecret: secret };
  }
  return res;
}

/** A started Game: White created it and Black redeemed its Invite Code. */
export interface Started {
  gameId: string;
  white: string;
  black: string;
  daysPerMove: number;
}

export async function start(opts: { creator?: Side; daysPerMove?: number } = {}): Promise<Started> {
  const creator = opts.creator ?? "white";
  const created = await create(creator, { daysPerMove: opts.daysPerMove });
  const redeemed = await redeem(created.inviteCode!);
  if (redeemed.status !== 200) throw new Error(`redeem failed: ${JSON.stringify(redeemed.json)}`);
  const secrets = { [creator]: created.seatSecret, [redeemed.json.side]: redeemed.json.seatSecret } as Record<Side, string>;
  return { gameId: created.gameId, white: secrets.white, black: secrets.black, daysPerMove: opts.daysPerMove ?? 3 };
}

export interface AppendBody {
  v?: string;
  seq: number;
  ply: number;
  kind: string;
  uci?: string;
  hash?: string;
  rematch?: { gameId: string; joinToken: string };
  end?: boolean;
}

export const append = (gameId: string, secret: string, e: AppendBody) =>
  call("POST", `/v1/games/${gameId}/events`, { secret, body: { v: "1.0", hash: digest(e.seq), ...e } });

export const events = (gameId: string, secret: string, since = 0) =>
  call("GET", `/v1/games/${gameId}/events?since=${since}`, { secret });

/** Plays [ucis] from the start, alternating White and Black, as entries 1..n. */
export async function play(game: Started, ucis: string[]): Promise<void> {
  for (const [i, uci] of ucis.entries()) {
    const ply = i + 1;
    const res = await append(game.gameId, ply % 2 === 1 ? game.white : game.black, { seq: ply, ply, kind: "move", uci });
    if (res.status !== 201) throw new Error(`move ${uci} failed: ${JSON.stringify(res.json)}`);
  }
}

export const stub = (gameId: string) => env.GAME.get(env.GAME.idFromString(gameId));

/** Rewrites the Game's stored metadata, to move its clocks into the past. */
export async function patchMeta(gameId: string, patch: (meta: any) => void): Promise<void> {
  await runInDurableObject(stub(gameId), (_instance, state) => {
    const row = state.storage.sql.exec<{ json: string }>("SELECT json FROM meta WHERE id = 1").one();
    const meta = JSON.parse(row.json);
    patch(meta);
    state.storage.sql.exec("UPDATE meta SET json = ? WHERE id = 1", JSON.stringify(meta));
  });
}

export async function tableCount(gameId: string): Promise<number> {
  return runInDurableObject(stub(gameId), (_instance, state) =>
    state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('meta', 'entries')").toArray()
      .length,
  );
}
