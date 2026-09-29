// The wire protocol (docs/protocol.md): constants, shapes, validation and errors. No chess here.

export const PROTOCOL = "1.0";
/** Every major the Relay serves; /health lists them (E9). */
export const MAJORS: readonly number[] = [1];

export const HOUR_MS = 3_600_000;
export const DAY_MS = 86_400_000;
export const INVITE_TTL_MS = 48 * HOUR_MS;
export const RETENTION_MS = 30 * DAY_MS;
/** The opponent counts as gone after this long without a ping (G3). */
export const PRESENCE_TIMEOUT_MS = 10_000;
export const MAX_SYNC_GAMES = 5;
export const MAX_ENTRIES = 2_000;
/** Request bodies larger than this get 413 body_too_large (R5). */
export const MAX_BODY_BYTES = 4_096;
/** WebSocket messages larger than this get an error (R5). */
export const MAX_WS_MESSAGE_BYTES = 256;
export const DAYS_PER_MOVE: readonly number[] = [1, 3, 7];

export type Side = "white" | "black";
export const SIDES: readonly Side[] = ["white", "black"];
export const opponentOf = (side: Side): Side => (side === "white" ? "black" : "white");
/** The side that plays Move number [ply] (ply 1 is White's first Move). */
export const sideOfPly = (ply: number): Side => (ply % 2 === 1 ? "white" : "black");

export const KINDS = [
  "move",
  "resign",
  "drawOffer",
  "drawAccept",
  "drawDecline",
  "claim",
  "rematchOffer",
  "rematchAccept",
  "rematchDecline",
] as const;
export type Kind = (typeof KINDS)[number];
export const TERMINAL_KINDS: ReadonlySet<Kind> = new Set(["resign", "drawAccept", "claim"]);
export const REMATCH_KINDS: ReadonlySet<Kind> = new Set(["rematchOffer", "rematchAccept", "rematchDecline"]);
/** The only kinds refused once the log holds MAX_ENTRIES; the others are bounded by the log closing (R4). */
export const CAPPED_KINDS: ReadonlySet<Kind> = new Set(["move", "drawOffer", "drawDecline"]);

export interface RematchRef {
  gameId: string;
  joinToken: string;
}

export interface Entry {
  v: string;
  gameId: string;
  seq: number;
  ply: number;
  side: Side;
  kind: Kind;
  uci?: string;
  /** Only on a `move` that ends the Game by the mover's rules core; closes the log (R2). */
  end?: true;
  hash: string;
  rematch?: RematchRef;
  serverTime: number;
}

/** What a phone sends to append: an Entry without the fields the Relay stamps. */
export type Append = Omit<Entry, "gameId" | "side" | "serverTime">;

export type InviteKind = "code" | "token";

export interface CreateRequest {
  v: string;
  side: Side;
  daysPerMove: number;
  invite: InviteKind;
}

export type ErrorCode =
  | "bad_request"
  | "unsupported_version"
  | "bad_seat_secret"
  | "not_your_turn"
  | "not_found"
  | "game_not_found"
  | "invite_not_found"
  | "method_not_allowed"
  | "version_mismatch"
  | "invite_used"
  | "invite_redeemed"
  | "game_not_started"
  | "seq_conflict"
  | "game_closed"
  | "rematch_already_offered"
  | "no_rematch_offer"
  | "rematch_answered"
  | "claim_too_early"
  | "draw_already_offered"
  | "no_draw_offer"
  | "game_not_over"
  | "log_full"
  | "body_too_large"
  | "upgrade_required"
  | "rate_limited";

const STATUS: Record<ErrorCode, number> = {
  bad_request: 400,
  unsupported_version: 400,
  bad_seat_secret: 401,
  not_your_turn: 403,
  not_found: 404,
  game_not_found: 404,
  invite_not_found: 404,
  method_not_allowed: 405,
  version_mismatch: 409,
  invite_used: 409,
  invite_redeemed: 409,
  game_not_started: 409,
  seq_conflict: 409,
  game_closed: 409,
  rematch_already_offered: 409,
  no_rematch_offer: 409,
  rematch_answered: 409,
  claim_too_early: 409,
  draw_already_offered: 409,
  no_draw_offer: 409,
  game_not_over: 409,
  log_full: 409,
  body_too_large: 413,
  upgrade_required: 426,
  rate_limited: 429,
};

/** An HTTP status and a JSON body: what every Relay operation returns, over RPC or HTTP. */
export interface Reply {
  status: number;
  body: unknown;
}

export interface ErrorBody {
  error: { code: ErrorCode; message: string } & Record<string, unknown>;
}

export const ok = (body: unknown, status = 200): Reply => ({ status, body });

export const fail = (code: ErrorCode, message: string, extra: Record<string, unknown> = {}): Reply => ({
  status: STATUS[code],
  body: { error: { code, message, ...extra } } satisfies ErrorBody,
});

/** Thrown by validators; turned into a Reply at the boundary. */
export class ProtocolError extends Error {
  constructor(readonly reply: Reply) {
    super((reply.body as ErrorBody).error.message);
  }
}

const bad = (message: string): never => {
  throw new ProtocolError(fail("bad_request", message));
};

export const majorOf = (v: string): number => Number(v.split(".")[0]);

/** Checks `v` is major.minor, served, and (when given) the path's major. */
export function checkVersion(v: unknown, pathMajor?: number): string {
  if (typeof v !== "string" || !/^\d{1,4}\.\d{1,4}$/.test(v)) bad('"v" must be "major.minor", such as "1.0"');
  const version = v as string;
  const major = majorOf(version);
  if (!MAJORS.includes(major) || (pathMajor !== undefined && major !== pathMajor)) {
    throw new ProtocolError(
      fail("unsupported_version", `This Relay serves majors ${MAJORS.join(", ")}; got ${version}`, {
        majors: [...MAJORS],
      }),
    );
  }
  return version;
}

const isObject = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);

export function asObject(x: unknown): Record<string, unknown> {
  if (!isObject(x)) bad("The body must be a JSON object");
  return x as Record<string, unknown>;
}

const HASH = /^[0-9a-f]{64}$/;
const UCI = /^[a-h][1-8][a-h][1-8][qrbn]?$/;
/** Seat secrets and join tokens: 32 bytes of unpadded base64url. */
export const TOKEN = /^[A-Za-z0-9_-]{43}$/;
/** A Durable Object id as the Worker hands it out. */
export const GAME_ID = /^[0-9a-f]{64}$/;

const isCount = (x: unknown): x is number => typeof x === "number" && Number.isSafeInteger(x) && x >= 0;

export function parseCreate(body: unknown, pathMajor: number): CreateRequest {
  const o = asObject(body);
  const v = checkVersion(o.v, pathMajor);
  if (!SIDES.includes(o.side as Side)) bad('"side" must be "white" or "black"');
  if (!DAYS_PER_MOVE.includes(o.daysPerMove as number)) bad(`"daysPerMove" must be one of ${DAYS_PER_MOVE.join(", ")}`);
  const invite = o.invite ?? "code";
  if (invite !== "code" && invite !== "token") bad('"invite" must be "code" or "token"');
  return { v, side: o.side as Side, daysPerMove: o.daysPerMove as number, invite: invite as InviteKind };
}

export function parseAppend(body: unknown, pathMajor: number): Append {
  const o = asObject(body);
  const v = checkVersion(o.v, pathMajor);
  if (!isCount(o.seq) || o.seq < 1) bad('"seq" must be an integer >= 1');
  if (!isCount(o.ply)) bad('"ply" must be an integer >= 0');
  if (!KINDS.includes(o.kind as Kind)) bad(`"kind" must be one of ${KINDS.join(", ")}`);
  const kind = o.kind as Kind;
  if (typeof o.hash !== "string" || !HASH.test(o.hash)) bad('"hash" must be 64 lowercase hex characters');
  const entry: Append = { v, seq: o.seq as number, ply: o.ply as number, kind, hash: o.hash as string };
  if (kind === "move") {
    if (typeof o.uci !== "string" || !UCI.test(o.uci)) bad('A "move" needs "uci", such as "e2e4" or "e7e8q"');
    entry.uci = o.uci as string;
    if (o.end !== undefined) {
      if (o.end !== true) bad('"end" is either true or absent');
      entry.end = true;
    }
  } else if (o.uci !== undefined || o.end !== undefined) {
    bad('Only a "move" carries "uci" or "end"');
  }
  if (kind === "rematchOffer") {
    const r = o.rematch;
    if (!isObject(r) || typeof r.gameId !== "string" || !GAME_ID.test(r.gameId) || typeof r.joinToken !== "string" || !TOKEN.test(r.joinToken)) {
      bad('A "rematchOffer" needs "rematch": { "gameId", "joinToken" } from creating the new Game');
    }
    const ref = r as Record<string, string>;
    entry.rematch = { gameId: ref.gameId!, joinToken: ref.joinToken! };
  } else if (o.rematch !== undefined) {
    bad('Only a "rematchOffer" carries "rematch"');
  }
  return entry;
}

/**
 * The body of a redeem (`{ v, seatSecret }`) or a join (`{ v, seatSecret, joinToken }`) (W9): the
 * phone chooses its own seat secret, so a retry after a lost response takes the same Seat again.
 */
export function parseSeatRequest(body: unknown, pathMajor: number, withJoinToken: boolean): { v: string; seatSecret: string; joinToken?: string } {
  const o = asObject(body);
  const v = checkVersion(o.v, pathMajor);
  if (typeof o.seatSecret !== "string" || !TOKEN.test(o.seatSecret)) {
    bad('Send "seatSecret": 32 random bytes the phone chose, as 43 base64url characters');
  }
  if (!withJoinToken) return { v, seatSecret: o.seatSecret as string };
  // Only a 43-character token: an Invite Code must not redeem at /join, around the rate limit (F11).
  if (typeof o.joinToken !== "string" || !TOKEN.test(o.joinToken)) bad('Send "joinToken", 43 base64url characters');
  return { v, seatSecret: o.seatSecret as string, joinToken: o.joinToken as string };
}

export interface SyncItem {
  gameId: string;
  seatSecret: string;
  since: number;
}

export function parseSync(body: unknown, pathMajor: number): SyncItem[] {
  const o = asObject(body);
  checkVersion(o.v, pathMajor);
  const games = o.games;
  if (!Array.isArray(games) || games.length < 1 || games.length > MAX_SYNC_GAMES) {
    bad(`"games" must list 1 to ${MAX_SYNC_GAMES} games`);
  }
  return (games as unknown[]).map((g) => {
    const item = asObject(g);
    if (typeof item.gameId !== "string") bad('Each game needs "gameId"');
    if (typeof item.seatSecret !== "string") bad('Each game needs "seatSecret"');
    const since = item.since ?? 0;
    if (!isCount(since)) bad('"since" must be an integer >= 0');
    return { gameId: item.gameId as string, seatSecret: item.seatSecret as string, since: since as number };
  });
}

export function parseSince(raw: string | null): number {
  if (raw === null || raw === "") return 0;
  if (!/^\d{1,9}$/.test(raw)) bad('"since" must be an integer >= 0');
  return Number(raw);
}
