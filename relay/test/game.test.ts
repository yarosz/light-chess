// A Correspondence Game's log: the happy path, compare-and-swap, retries, Seats and ply parity.
import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { sha256Hex } from "../src/secrets";
import { append, call, create, digest, events, play, redeem, start, stub } from "./helpers";

describe("health and versions", () => {
  it("lists the served majors", async () => {
    const res = await call("GET", "/health");
    expect(res.status).toBe(200);
    expect(res.json).toEqual({ status: "ok", protocol: "1.0", majors: [1] });
  });

  it("refuses an unsupported major in the path", async () => {
    const res = await call("POST", "/v2/games", { body: { v: "2.0", side: "white", daysPerMove: 3 } });
    expect(res.status).toBe(400);
    expect(res.json.error).toMatchObject({ code: "unsupported_version", majors: [1] });
  });

  it("refuses a body version whose major is not served", async () => {
    const res = await call("POST", "/v1/games", { body: { v: "2.0", side: "white", daysPerMove: 3 } });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("unsupported_version");
  });

  it("refuses an append whose major is not served", async () => {
    const game = await start();
    const res = await append(game.gameId, game.white, { v: "2.1", seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(res.json.error.code).toBe("unsupported_version");
  });

  it("accepts a newer minor and keeps the Game pinned to its own version", async () => {
    const game = await start();
    const res = await append(game.gameId, game.white, { v: "1.4", seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(res.status).toBe(201);
    expect(res.json.entry.v).toBe("1.4");
    expect((await events(game.gameId, game.black)).json.v).toBe("1.0");
  });

  it("returns 404 for unknown endpoints and 405 for a wrong method", async () => {
    expect((await call("GET", "/v1/nope")).json.error.code).toBe("not_found");
    expect((await call("GET", "/")).json.error.code).toBe("not_found");
    const res = await call("PUT", "/v1/games");
    expect(res.status).toBe(405);
    expect(res.json.error.code).toBe("method_not_allowed");
  });

  it("answers a malformed percent-escape in the path with bad_request, not a crash", async () => {
    const game = await start();
    const res = await call("GET", "/v1/games/%E0%A4%A/events", { secret: game.white });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("bad_request");
  });
});

describe("happy path", () => {
  it("create, redeem, alternate Moves, resign", async () => {
    const created = await create("white", { daysPerMove: 7 });
    expect(created.side).toBe("white");
    expect(created.inviteCode).toMatch(/^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
    expect(created.seatSecret).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.inviteExpiresAt - Date.now()).toBeGreaterThan(47 * 3_600_000);

    const waiting = await events(created.gameId, created.seatSecret);
    expect(waiting.json).toMatchObject({ status: "waiting", side: "white", latestSeq: 0, entries: [], startedAt: null });

    const redeemed = await redeem(created.inviteCode!);
    expect(redeemed.status).toBe(200);
    expect(redeemed.json).toMatchObject({ v: "1.0", gameId: created.gameId, side: "black", daysPerMove: 7 });
    expect(redeemed.json.seatSecret).not.toBe(created.seatSecret);
    const white = created.seatSecret;
    const black = redeemed.json.seatSecret;
    const gameId = created.gameId;

    const moves = ["e2e4", "e7e5", "g1f3", "b8c6"];
    for (const [i, uci] of moves.entries()) {
      const res = await append(gameId, i % 2 === 0 ? white : black, { seq: i + 1, ply: i + 1, kind: "move", uci });
      expect(res.status).toBe(201);
      expect(res.json.entry).toMatchObject({ v: "1.0", gameId, seq: i + 1, ply: i + 1, kind: "move", uci, hash: digest(i + 1) });
      expect(res.json.entry.side).toBe(i % 2 === 0 ? "white" : "black");
      expect(typeof res.json.entry.serverTime).toBe("number");
    }

    // Black resigns on White's turn: resignation needs no turn.
    const resign = await append(gameId, black, { seq: 5, ply: 4, kind: "resign" });
    expect(resign.status).toBe(201);
    expect(resign.json.entry).toMatchObject({ seq: 5, ply: 4, side: "black", kind: "resign" });
    expect(resign.json.entry.uci).toBeUndefined();

    const read = await events(gameId, white);
    expect(read.json).toMatchObject({ status: "closed", side: "white", latestSeq: 5, latestPly: 4 });
    expect(read.json.entries.map((e: any) => e.kind)).toEqual(["move", "move", "move", "move", "resign"]);

    const since = await events(gameId, black, 3);
    expect(since.json.side).toBe("black");
    expect(since.json.entries.map((e: any) => e.seq)).toEqual([4, 5]);
  });

  it("lets the creator take Black; White still moves first", async () => {
    const game = await start({ creator: "black" });
    const res = await append(game.gameId, game.white, { seq: 1, ply: 1, kind: "move", uci: "d2d4" });
    expect(res.status).toBe(201);
    expect(res.json.entry.side).toBe("white");
  });

  it("keeps the ply across Game Events that aren't Moves", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    expect((await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" })).status).toBe(201);
    expect((await append(game.gameId, game.black, { seq: 3, ply: 1, kind: "drawDecline" })).status).toBe(201);
    expect((await append(game.gameId, game.black, { seq: 4, ply: 2, kind: "move", uci: "e7e5" })).status).toBe(201);
    const res = await append(game.gameId, game.white, { seq: 5, ply: 3, kind: "drawOffer" });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("bad_request");
  });
});

describe("compare-and-swap", () => {
  it("refuses a second entry at a taken seq", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    // Black and White both think entry 2 is theirs: Black's Move lands first.
    expect((await append(game.gameId, game.black, { seq: 2, ply: 2, kind: "move", uci: "c7c5" })).status).toBe(201);
    const late = await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    expect(late.status).toBe(409);
    expect(late.json.error).toMatchObject({ code: "seq_conflict", latestSeq: 2 });
  });

  it("refuses a different entry at a taken seq from the same Seat", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    const res = await append(game.gameId, game.white, { seq: 1, ply: 1, kind: "move", uci: "d2d4" });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("seq_conflict");
  });

  it("refuses a skipped seq", async () => {
    const game = await start();
    const res = await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "move", uci: "e2e4" });
    expect(res.status).toBe(409);
    expect(res.json.error).toMatchObject({ code: "seq_conflict", latestSeq: 0 });
  });

  it("serialises concurrent appends at the same seq", async () => {
    const game = await start();
    const [a, b] = await Promise.all([
      append(game.gameId, game.white, { seq: 1, ply: 1, kind: "move", uci: "e2e4" }),
      append(game.gameId, game.white, { seq: 1, ply: 1, kind: "move", uci: "d2d4" }),
    ]);
    expect([a.status, b.status].sort()).toEqual([201, 409]);
    expect((await events(game.gameId, game.black)).json.latestSeq).toBe(1);
  });

  it("serialises two appends at the same seq started in one tick inside the Durable Object", async () => {
    const game = await start();
    const white = await sha256Hex(game.white);
    const move = (uci: string) => ({ v: "1.0", seq: 1, ply: 1, kind: "move" as const, uci, hash: digest(1) });
    const statuses = await runInDurableObject(stub(game.gameId), async (instance) => {
      const replies = await Promise.all([instance.append(white, move("e2e4")), instance.append(white, move("d2d4"))]);
      return replies.map((r) => r.status).sort();
    });
    expect(statuses).toEqual([201, 409]);
  });
});

describe("idempotent retry", () => {
  it("answers an identical re-append with the stored entry", async () => {
    const game = await start();
    const body = { seq: 1, ply: 1, kind: "move", uci: "e2e4" };
    const first = await append(game.gameId, game.white, body);
    const retry = await append(game.gameId, game.white, body);
    expect(first.status).toBe(201);
    expect(retry.status).toBe(200);
    expect(retry.json.entry).toEqual(first.json.entry);
    const read = await events(game.gameId, game.white);
    expect(read.json.entries).toHaveLength(1);
  });

  it("answers a retry of an old entry after the log has moved on", async () => {
    const game = await start();
    await play(game, ["e2e4", "e7e5", "g1f3"]);
    const retry = await append(game.gameId, game.black, { seq: 2, ply: 2, kind: "move", uci: "e7e5" });
    expect(retry.status).toBe(200);
    expect(retry.json.entry.seq).toBe(2);
  });

  it("answers a retried resignation even though the log is closed", async () => {
    const game = await start();
    const body = { seq: 1, ply: 0, kind: "resign" };
    expect((await append(game.gameId, game.white, body)).status).toBe(201);
    expect((await append(game.gameId, game.white, body)).status).toBe(200);
  });

  it("does not let the other Seat claim a stored entry", async () => {
    const game = await start();
    await append(game.gameId, game.white, { seq: 1, ply: 0, kind: "resign" });
    const res = await append(game.gameId, game.black, { seq: 1, ply: 0, kind: "resign" });
    expect(res.json.error.code).toBe("seq_conflict");
  });
});

describe("Seats and turns", () => {
  it("refuses a missing or wrong seat secret", async () => {
    const game = await start();
    const missing = await call("POST", `/v1/games/${game.gameId}/events`, {
      body: { v: "1.0", seq: 1, ply: 1, kind: "move", uci: "e2e4", hash: digest(1) },
    });
    expect(missing.status).toBe(401);
    expect(missing.json.error.code).toBe("bad_seat_secret");
    const wrong = await append(game.gameId, "A".repeat(43), { seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(wrong.status).toBe(401);
    expect(wrong.json.error.code).toBe("bad_seat_secret");
    expect((await events(game.gameId, "A".repeat(43))).status).toBe(401);
  });

  it("refuses another Game's seat secret", async () => {
    const a = await start();
    const b = await start();
    const res = await append(a.gameId, b.white, { seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(res.json.error.code).toBe("bad_seat_secret");
  });

  it("refuses a Move by the side not to move, from ply parity", async () => {
    const game = await start();
    const blackFirst = await append(game.gameId, game.black, { seq: 1, ply: 1, kind: "move", uci: "e7e5" });
    expect(blackFirst.status).toBe(403);
    expect(blackFirst.json.error.code).toBe("not_your_turn");
    await play(game, ["e2e4"]);
    const whiteTwice = await append(game.gameId, game.white, { seq: 2, ply: 2, kind: "move", uci: "d2d4" });
    expect(whiteTwice.status).toBe(403);
    expect(whiteTwice.json.error.code).toBe("not_your_turn");
  });

  it("refuses appends before the invite is redeemed", async () => {
    const created = await create();
    const res = await append(created.gameId, created.seatSecret, { seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("game_not_started");
  });

  it("refuses malformed entries", async () => {
    const game = await start();
    const cases = [
      { seq: 1, ply: 1, kind: "move" }, // no uci
      { seq: 1, ply: 1, kind: "move", uci: "e2e9" },
      { seq: 1, ply: 0, kind: "resign", uci: "e2e4" },
      { seq: 1, ply: 0, kind: "takeback" }, // contradiction 6: no takeback kind
      { seq: 0, ply: 0, kind: "resign" },
      { seq: 1, ply: 1, kind: "move", uci: "e2e4", hash: "ABC" },
      { seq: 1, ply: 0, kind: "rematchOffer" },
    ];
    for (const body of cases) {
      const res = await append(game.gameId, game.white, body as any);
      expect(res.status, JSON.stringify(body)).toBe(400);
      expect(res.json.error.code).toBe("bad_request");
    }
    const notJson = await call("POST", `/v1/games/${game.gameId}/events`, {
      secret: game.white,
      body: undefined,
      headers: { "Content-Type": "application/json" },
    });
    expect(notJson.json.error.code).toBe("bad_request");
  });

  it("returns game_not_found for a malformed or unknown Game id", async () => {
    const game = await start();
    expect((await events("nope", game.white)).json.error.code).toBe("game_not_found");
    expect((await events("0".repeat(64), game.white)).json.error.code).toBe("game_not_found");
  });
});
