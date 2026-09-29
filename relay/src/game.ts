// One Correspondence Game: its Seats, its invite and its log of Game Events (ADR 0002).
//
// Every operation reads and writes SQLite synchronously between two awaits, so the Durable Object's
// single thread serialises them: an append races nothing, and a cancel and a redeem of the same
// invite can't both win. Secrets arrive already hashed (the Worker hashes them), and live() is
// synchronous, so no await splits a check from its write (a test in invite.test.ts holds this).

import { DurableObject } from "cloudflare:workers";
import {
  CAPPED_KINDS,
  DAY_MS,
  INVITE_TTL_MS,
  MAX_ENTRIES,
  MAX_WS_MESSAGE_BYTES,
  PRESENCE_TIMEOUT_MS,
  RETENTION_MS,
  TERMINAL_KINDS,
  fail,
  majorOf,
  ok,
  opponentOf,
  sideOfPly,
  type Append,
  type CreateRequest,
  type Entry,
  type InviteKind,
  type Kind,
  type Reply,
  type Side,
} from "./protocol";
import { sha256Hex } from "./secrets";

interface Meta {
  v: string;
  gameId: string;
  createdAt: number;
  daysPerMove: number;
  creatorSide: Side;
  /** SHA-256 of each Seat's secret; the joining Seat is null until the invite is redeemed. */
  seats: Record<Side, string | null>;
  /** The open invite; null once redeemed. */
  invite: { kind: InviteKind; hash: string; expiresAt: number } | null;
  /** The redeemed invite's hash, so a wrong token still gets invite_not_found and the right one invite_used. */
  usedInviteHash: string | null;
  startedAt: number | null;
  /** When the alarm deletes the Game. */
  deleteAt: number;
  latestSeq: number;
  latestPly: number;
  lastMoveAt: number | null;
  closed: boolean;
  /** The Seat whose draw offer is open, from kinds alone (R1). */
  openDrawBy: Side | null;
  rematch: { offeredBy: Side; gameId: string; answer: "accept" | "decline" | null } | null;
}

interface EntryRow {
  seq: number;
  ply: number;
  side: Side;
  kind: Kind;
  uci: string | null;
  end_game: number | null;
  hash: string;
  v: string;
  rematch_game: string | null;
  rematch_token: string | null;
  server_time: number;
  [key: string]: SqlStorageValue;
}

interface SocketAttachment {
  side: Side;
  /** When the Relay last heard from this socket (open or ping). */
  lastSeen: number;
}

/** Closes a socket because another socket of the same Seat replaced it. */
const CLOSE_REPLACED = 4001;
/** Closes a socket because its Game was deleted. */
const CLOSE_DELETED = 4004;

export class CorrespondenceGame extends DurableObject<Env> {
  private get sql(): SqlStorage {
    return this.ctx.storage.sql;
  }

  // Tables are created by create(), never by a read: a guessed Invite Code must leave nothing behind.
  private exists(): boolean {
    return this.sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").toArray().length > 0;
  }

  private readMeta(): Meta | null {
    if (!this.exists()) return null;
    const row = this.sql.exec<{ json: string }>("SELECT json FROM meta WHERE id = 1").toArray()[0];
    return row ? (JSON.parse(row.json) as Meta) : null;
  }

  private writeMeta(meta: Meta): void {
    this.sql.exec("INSERT OR REPLACE INTO meta (id, json) VALUES (1, ?)", JSON.stringify(meta));
  }

  /**
   * The Game, or null if it doesn't exist. An invite past its expiry deletes its Game on sight.
   * Synchronous on purpose: an `await` here would let a concurrent cancel or redeem run between an
   * operation's read of the Game and its write, and both could win.
   */
  private live(now: number): Meta | null {
    const meta = this.readMeta();
    if (meta?.invite && now >= meta.invite.expiresAt) {
      void this.wipe();
      return null;
    }
    return meta;
  }

  /**
   * Deletes the Game. The tables are dropped before the first `await`, so from the moment wipe() is
   * called no other operation can see the Game; the alarm and the rest of storage follow.
   */
  private async wipe(): Promise<void> {
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("DROP TABLE IF EXISTS entries");
      this.sql.exec("DROP TABLE IF EXISTS meta");
    });
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.close(CLOSE_DELETED, "game deleted");
      } catch {
        // Already closed.
      }
    }
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
  }

  private seatOf(meta: Meta, secretHash: string): Side | null {
    if (meta.seats.white === secretHash) return "white";
    if (meta.seats.black === secretHash) return "black";
    return null;
  }

  // ---- RPC, called by the Worker ----------------------------------------------------------

  /** Creates the Game. Returns null if this Durable Object already holds one (an Invite Code collision). */
  async create(req: CreateRequest, seatHash: string, inviteHash: string): Promise<Reply | null> {
    if (this.exists()) return null;
    const now = Date.now();
    const meta: Meta = {
      v: req.v,
      gameId: this.ctx.id.toString(),
      createdAt: now,
      daysPerMove: req.daysPerMove,
      creatorSide: req.side,
      seats: { white: null, black: null },
      invite: { kind: req.invite, hash: inviteHash, expiresAt: now + INVITE_TTL_MS },
      startedAt: null,
      deleteAt: now + INVITE_TTL_MS,
      latestSeq: 0,
      latestPly: 0,
      lastMoveAt: null,
      closed: false,
      openDrawBy: null,
      rematch: null,
      usedInviteHash: null,
    };
    meta.seats[req.side] = seatHash;
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK (id = 1), json TEXT NOT NULL)");
      this.sql.exec(`CREATE TABLE entries (
        seq INTEGER PRIMARY KEY,
        ply INTEGER NOT NULL,
        side TEXT NOT NULL,
        kind TEXT NOT NULL,
        uci TEXT,
        end_game INTEGER,
        hash TEXT NOT NULL,
        v TEXT NOT NULL,
        rematch_game TEXT,
        rematch_token TEXT,
        server_time INTEGER NOT NULL
      )`);
      this.writeMeta(meta);
    });
    await this.ctx.storage.setAlarm(meta.deleteAt);
    return ok(
      {
        v: meta.v,
        gameId: meta.gameId,
        side: req.side,
        daysPerMove: meta.daysPerMove,
        inviteExpiresAt: meta.invite!.expiresAt,
        serverTime: now,
      },
      201,
    );
  }

  /**
   * Takes the open Seat with the invite whose SHA-256 is [inviteHash], for the phone whose chosen seat
   * secret hashes to [seatHash] (W9). A repeat with the same invite and the same secret, after a lost
   * response, answers the same Seat again; the same invite with another secret is `invite_used`.
   */
  async redeem(v: string, inviteHash: string, seatHash: string): Promise<Reply> {
    const now = Date.now();
    const meta = this.live(now);
    if (!meta) return fail("invite_not_found", "No such invite, or it expired or was cancelled");
    const notFound = fail("invite_not_found", "No such invite, or it expired or was cancelled");
    const side = opponentOf(meta.creatorSide);
    const seated = () =>
      ok({ v: meta.v, gameId: meta.gameId, side, daysPerMove: meta.daysPerMove, startedAt: meta.startedAt, serverTime: now });
    if (!meta.invite) {
      if (meta.usedInviteHash !== inviteHash) return notFound;
      return meta.seats[side] === seatHash ? seated() : fail("invite_used", "This invite was already used");
    }
    if (meta.invite.hash !== inviteHash) return notFound;
    if (majorOf(v) !== majorOf(meta.v)) {
      return fail("version_mismatch", `This Game uses protocol ${meta.v}`, { gameV: meta.v });
    }
    // The two Seats hold different secrets, or one bearer could act as either.
    if (meta.seats[meta.creatorSide] === seatHash) return fail("bad_request", "Choose a fresh seat secret");
    meta.seats[side] = seatHash;
    meta.usedInviteHash = meta.invite.hash;
    meta.invite = null;
    meta.startedAt = now;
    meta.deleteAt = now + RETENTION_MS;
    this.writeMeta(meta);
    await this.ctx.storage.setAlarm(meta.deleteAt);
    return seated();
  }

  /** Cancels the open invite and deletes the Game (G2). Only the creator's Seat can. */
  async cancel(secretHash: string): Promise<Reply> {
    const meta = this.live(Date.now());
    if (!meta) return fail("game_not_found", "No such Game");
    if (meta.seats[meta.creatorSide] !== secretHash) return fail("bad_seat_secret", "Only the creator can cancel");
    if (!meta.invite) return fail("invite_redeemed", "The invite was already redeemed; the Game has started");
    await this.wipe();
    return ok({ cancelled: true, gameId: meta.gameId });
  }

  /** The Game as its Seat [secretHash] sees it, with the entries after [since]. */
  async read(secretHash: string, since: number): Promise<Reply> {
    const now = Date.now();
    const meta = this.live(now);
    if (!meta) return fail("game_not_found", "No such Game");
    const side = this.seatOf(meta, secretHash);
    if (!side) return fail("bad_seat_secret", "This seat secret belongs to neither Seat of this Game");
    const entries = this.sql
      .exec<EntryRow>("SELECT * FROM entries WHERE seq > ? ORDER BY seq", since)
      .toArray()
      .map((row) => this.toEntry(meta, row));
    return ok({
      v: meta.v,
      gameId: meta.gameId,
      status: meta.invite ? "waiting" : meta.closed ? "closed" : "active",
      side,
      daysPerMove: meta.daysPerMove,
      createdAt: meta.createdAt,
      startedAt: meta.startedAt,
      inviteExpiresAt: meta.invite?.expiresAt ?? null,
      latestSeq: meta.latestSeq,
      latestPly: meta.latestPly,
      openDrawBy: meta.openDrawBy ?? null,
      deadline: meta.invite || meta.closed ? null : deadlineOf(meta),
      rematch: meta.rematch,
      serverTime: now,
      entries,
    });
  }

  /** Appends one Game Event (docs/protocol.md "Appending"). */
  async append(secretHash: string, e: Append): Promise<Reply> {
    const now = Date.now();
    const meta = this.live(now);
    if (!meta) return fail("game_not_found", "No such Game");
    const side = this.seatOf(meta, secretHash);
    if (!side) return fail("bad_seat_secret", "This seat secret belongs to neither Seat of this Game");
    if (meta.invite) return fail("game_not_started", "Nobody has redeemed the invite yet");
    if (majorOf(e.v) !== majorOf(meta.v)) {
      return fail("version_mismatch", `This Game uses protocol ${meta.v}`, { gameV: meta.v });
    }

    // Compare-and-swap on seq, with an identical retry answered from the log.
    if (e.seq <= meta.latestSeq) {
      const row = this.sql.exec<EntryRow>("SELECT * FROM entries WHERE seq = ?", e.seq).toArray()[0];
      const stored = row && this.toEntry(meta, row);
      if (stored && sameEvent(stored, e, side)) return ok({ entry: stored });
      return seqConflict(e.seq, meta.latestSeq);
    }
    if (e.seq !== meta.latestSeq + 1) return seqConflict(e.seq, meta.latestSeq);
    if (meta.latestSeq >= MAX_ENTRIES && CAPPED_KINDS.has(e.kind)) {
      return fail("log_full", `A Game holds at most ${MAX_ENTRIES} entries before it ends`);
    }

    const expectedPly = e.kind === "move" ? meta.latestPly + 1 : meta.latestPly;
    if (e.ply !== expectedPly) {
      return fail("bad_request", `Entry ${e.seq} (${e.kind}) must have ply ${expectedPly}`);
    }

    const refusal = this.refusal(meta, side, e, now);
    if (refusal) return refusal;

    const entry: Entry = { ...e, gameId: meta.gameId, side, serverTime: now };
    meta.latestSeq = e.seq;
    meta.latestPly = e.ply;
    if (e.kind === "move") meta.lastMoveAt = now;
    if (e.kind === "drawOffer") meta.openDrawBy = side;
    if (e.kind === "move" || e.kind === "drawDecline") meta.openDrawBy = null;
    if (TERMINAL_KINDS.has(e.kind) || e.end) {
      meta.closed = true;
      meta.openDrawBy = null;
    }
    if (e.kind === "rematchOffer") meta.rematch = { offeredBy: side, gameId: e.rematch!.gameId, answer: null };
    if (e.kind === "rematchAccept") meta.rematch!.answer = "accept";
    if (e.kind === "rematchDecline") meta.rematch!.answer = "decline";
    meta.deleteAt = now + RETENTION_MS;
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        "INSERT INTO entries (seq, ply, side, kind, uci, end_game, hash, v, rematch_game, rematch_token, server_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        entry.seq,
        entry.ply,
        entry.side,
        entry.kind,
        entry.uci ?? null,
        entry.end ? 1 : null,
        entry.hash,
        entry.v,
        entry.rematch?.gameId ?? null,
        entry.rematch?.joinToken ?? null,
        entry.serverTime,
      );
      this.writeMeta(meta);
    });
    await this.ctx.storage.setAlarm(meta.deleteAt);
    this.broadcast({ type: "entry", entry });
    return ok({ entry }, 201);
  }

  /** Why [side] may not append [e] now, from kind, ply parity and bookkeeping alone; null if it may. */
  private refusal(meta: Meta, side: Side, e: Append, now: number): Reply | null {
    switch (e.kind) {
      case "rematchOffer":
        if (!meta.closed) return fail("game_not_over", "A rematch can be offered only once the log is closed");
        if (meta.rematch) return fail("rematch_already_offered", "A Game gets one rematch offer");
        return null;
      case "rematchAccept":
      case "rematchDecline":
        if (!meta.rematch) return fail("no_rematch_offer", "There is no rematch offer to answer");
        if (meta.rematch.answer) return fail("rematch_answered", "The rematch offer was already answered");
        if (meta.rematch.offeredBy === side) return fail("not_your_turn", "Only the other Seat can answer a rematch offer");
        return null;
      default: {
        if (meta.closed) return fail("game_closed", "The log is closed; only the rematch kinds are accepted");
        const toMove = sideOfPly(meta.latestPly + 1);
        switch (e.kind) {
          case "move":
            if (side !== toMove) return fail("not_your_turn", `Ply ${e.ply} is ${toMove}'s Move`);
            return null;
          case "claim": {
            if (side === toMove) return fail("not_your_turn", "Only the side not to move can claim a timeout");
            const deadline = deadlineOf(meta);
            if (now < deadline) return fail("claim_too_early", "The time for this Move has not run out", { deadline });
            return null;
          }
          // R1: a draw is offered by the side that has just moved, and answered by the side to move.
          case "drawOffer":
            if (side === toMove || meta.latestPly < 1) {
              return fail("not_your_turn", "A draw is offered with your own Move, by the side not to move");
            }
            if (meta.openDrawBy) return fail("draw_already_offered", "Your draw offer is still open");
            return null;
          case "drawAccept":
          case "drawDecline":
            if (side !== toMove || meta.openDrawBy !== opponentOf(side)) {
              return fail("no_draw_offer", "There is no open draw offer from the other Seat to answer");
            }
            return null;
          default:
            return null;
        }
      }
    }
  }

  private toEntry(meta: Meta, row: EntryRow): Entry {
    const entry: Entry = {
      v: row.v,
      gameId: meta.gameId,
      seq: row.seq,
      ply: row.ply,
      side: row.side,
      kind: row.kind,
      hash: row.hash,
      serverTime: row.server_time,
    };
    if (row.uci !== null) entry.uci = row.uci;
    if (row.end_game) entry.end = true;
    if (row.rematch_game !== null) entry.rematch = { gameId: row.rematch_game, joinToken: row.rematch_token! };
    return entry;
  }

  // ---- Retention ---------------------------------------------------------------------------

  async alarm(): Promise<void> {
    const meta = this.readMeta();
    if (!meta) return;
    if (Date.now() >= meta.deleteAt) await this.wipe();
    else await this.ctx.storage.setAlarm(meta.deleteAt);
  }

  // ---- Presence over WebSocket Hibernation (G3) --------------------------------------------

  async fetch(request: Request): Promise<Response> {
    const secret = /^Bearer (\S+)$/.exec(request.headers.get("Authorization") ?? "")?.[1];
    const secretHash = secret ? await sha256Hex(secret) : null;
    const now = Date.now();
    const meta = this.live(now);
    if (!meta) return respond(fail("game_not_found", "No such Game"));
    const side = secretHash ? this.seatOf(meta, secretHash) : null;
    if (!side) return respond(fail("bad_seat_secret", "This seat secret belongs to neither Seat of this Game"));
    if (meta.invite) return respond(fail("game_not_started", "Nobody has redeemed the invite yet"));

    for (const old of this.ctx.getWebSockets(side)) {
      try {
        old.close(CLOSE_REPLACED, "replaced by a newer socket");
      } catch {
        // Already closed.
      }
    }
    const [client, server] = Object.values(new WebSocketPair()) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server, [side]);
    server.serializeAttachment({ side, lastSeen: now } satisfies SocketAttachment);
    this.broadcastPresence();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    let type: unknown;
    try {
      const small = typeof message === "string" && new TextEncoder().encode(message).length <= MAX_WS_MESSAGE_BYTES;
      type = small ? (JSON.parse(message) as { type?: unknown }).type : undefined;
    } catch {
      type = undefined;
    }
    if (type !== "ping") {
      ws.send(JSON.stringify({ type: "error", code: "bad_request" }));
      return;
    }
    const attachment = ws.deserializeAttachment() as SocketAttachment;
    attachment.lastSeen = Date.now();
    ws.serializeAttachment(attachment);
    ws.send(JSON.stringify(this.presenceFor(attachment.side)));
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try {
      ws.close(code === 1005 ? 1000 : code, reason);
    } catch {
      // The runtime already completed the close.
    }
    this.broadcastPresence(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.broadcastPresence(ws);
  }

  private openSockets(except?: WebSocket): WebSocket[] {
    return this.ctx.getWebSockets().filter((ws) => ws !== except && ws.readyState === WebSocket.OPEN);
  }

  private isHere(side: Side, except?: WebSocket): boolean {
    const now = Date.now();
    return this.openSockets(except).some((ws) => {
      const a = ws.deserializeAttachment() as SocketAttachment;
      return a.side === side && now - a.lastSeen < PRESENCE_TIMEOUT_MS;
    });
  }

  private presenceFor(side: Side, except?: WebSocket) {
    const opponentHere = this.isHere(opponentOf(side), except);
    return {
      type: "presence",
      live: opponentHere && this.isHere(side, except),
      opponent: opponentHere ? "here" : "gone",
      serverTime: Date.now(),
    };
  }

  private broadcastPresence(except?: WebSocket): void {
    for (const ws of this.openSockets(except)) {
      const { side } = ws.deserializeAttachment() as SocketAttachment;
      ws.send(JSON.stringify(this.presenceFor(side, except)));
    }
  }

  private broadcast(message: unknown): void {
    const text = JSON.stringify(message);
    for (const ws of this.openSockets()) ws.send(text);
  }
}

function sameEvent(stored: Entry, e: Append, side: Side): boolean {
  return (
    stored.side === side &&
    stored.kind === e.kind &&
    stored.ply === e.ply &&
    stored.hash === e.hash &&
    stored.uci === e.uci &&
    stored.end === e.end &&
    stored.rematch?.gameId === e.rematch?.gameId &&
    stored.rematch?.joinToken === e.rematch?.joinToken
  );
}

/** When the side to move's time runs out: daysPerMove after the latest Move, or after the start (C2). */
function deadlineOf(meta: Meta): number {
  return (meta.lastMoveAt ?? meta.startedAt!) + meta.daysPerMove * DAY_MS;
}

function seqConflict(seq: number, latestSeq: number): Reply {
  return fail("seq_conflict", `Entry ${seq} can't be appended; the latest is ${latestSeq}. Fetch events since your last seq`, {
    latestSeq,
  });
}

export function respond(reply: Reply, headers: Record<string, string> = {}): Response {
  return Response.json(reply.body, { status: reply.status, headers });
}
