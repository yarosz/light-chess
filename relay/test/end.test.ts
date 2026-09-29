// The end of a Game: terminal kinds, timeout claims and rematches (C5, E8, contradiction 9).
import { describe, expect, it } from "vitest";
import { append, create, events, join, patchMeta, play, start, type Started } from "./helpers";

const DAY = 86_400_000;

async function resigned(): Promise<Started> {
  const game = await start();
  await play(game, ["e2e4", "e7e5"]);
  expect((await append(game.gameId, game.black, { seq: 3, ply: 2, kind: "resign" })).status).toBe(201);
  return game;
}

describe("after a terminal entry", () => {
  it("refuses every kind except the rematch kinds", async () => {
    const game = await resigned();
    const refused = [
      { seq: 4, ply: 3, kind: "move", uci: "g1f3" },
      { seq: 4, ply: 2, kind: "resign" },
      { seq: 4, ply: 2, kind: "drawOffer" },
      { seq: 4, ply: 2, kind: "drawAccept" },
      { seq: 4, ply: 2, kind: "drawDecline" },
      { seq: 4, ply: 2, kind: "claim" },
    ];
    for (const body of refused) {
      const res = await append(game.gameId, game.white, body);
      expect(res.status, body.kind).toBe(409);
      expect(res.json.error.code).toBe("game_closed");
    }
  });

  it("closes the log on drawAccept", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    expect((await append(game.gameId, game.black, { seq: 3, ply: 1, kind: "drawAccept" })).status).toBe(201);
    const res = await append(game.gameId, game.black, { seq: 4, ply: 2, kind: "move", uci: "e7e5" });
    expect(res.json.error.code).toBe("game_closed");
    expect((await events(game.gameId, game.white)).json.status).toBe("closed");
  });

  it("keeps the log open after drawOffer and drawDecline", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    await append(game.gameId, game.black, { seq: 3, ply: 1, kind: "drawDecline" });
    expect((await events(game.gameId, game.white)).json.status).toBe("active");
  });
});

describe("timeout claims", () => {
  it("refuses a claim before the deadline, with the deadline", async () => {
    const game = await start({ daysPerMove: 1 });
    await play(game, ["e2e4"]);
    const res = await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "claim" });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("claim_too_early");
    const lastMove = (await events(game.gameId, game.white)).json.entries[0].serverTime;
    expect(res.json.error.deadline).toBe(lastMove + DAY);
  });

  it("refuses a claim by the side to move", async () => {
    const game = await start({ daysPerMove: 1 });
    await play(game, ["e2e4"]);
    await patchMeta(game.gameId, (m) => (m.lastMoveAt -= 2 * DAY));
    const res = await append(game.gameId, game.black, { seq: 2, ply: 1, kind: "claim" });
    expect(res.status).toBe(403);
    expect(res.json.error.code).toBe("not_your_turn");
  });

  it("accepts a claim once the side to move's time has run out, and closes the log", async () => {
    const game = await start({ daysPerMove: 3 });
    await play(game, ["e2e4"]);
    await patchMeta(game.gameId, (m) => (m.lastMoveAt -= 3 * DAY));
    const res = await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "claim" });
    expect(res.status).toBe(201);
    expect(res.json.entry).toMatchObject({ kind: "claim", side: "white" });
    const late = await append(game.gameId, game.black, { seq: 3, ply: 2, kind: "move", uci: "e7e5" });
    expect(late.json.error.code).toBe("game_closed");
  });

  it("runs White's first clock from when the Game started", async () => {
    const game = await start({ daysPerMove: 1 });
    expect((await append(game.gameId, game.black, { seq: 1, ply: 0, kind: "claim" })).json.error.code).toBe(
      "claim_too_early",
    );
    await patchMeta(game.gameId, (m) => (m.startedAt -= DAY));
    expect((await append(game.gameId, game.black, { seq: 1, ply: 0, kind: "claim" })).status).toBe(201);
  });

  it("does not restart the clock on a draw offer", async () => {
    const game = await start({ daysPerMove: 1 });
    await play(game, ["e2e4"]);
    await patchMeta(game.gameId, (m) => (m.lastMoveAt -= DAY));
    expect((await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" })).status).toBe(201);
    expect((await append(game.gameId, game.white, { seq: 3, ply: 1, kind: "claim" })).status).toBe(201);
  });
});

describe("late Moves (R3)", () => {
  it("accepts a Move after its deadline, which closes the claim window and restarts the clock", async () => {
    const game = await start({ daysPerMove: 1 });
    await play(game, ["e2e4"]);
    await patchMeta(game.gameId, (m) => (m.lastMoveAt -= 2 * DAY));
    const late = await append(game.gameId, game.black, { seq: 2, ply: 2, kind: "move", uci: "e7e5" });
    expect(late.status).toBe(201);
    // White can no longer claim Black's lapsed time; now White is the side to move.
    const claim = await append(game.gameId, game.white, { seq: 3, ply: 2, kind: "claim" });
    expect(claim.status).toBe(403);
    expect(claim.json.error.code).toBe("not_your_turn");
    const early = await append(game.gameId, game.black, { seq: 3, ply: 2, kind: "claim" });
    expect(early.json.error).toMatchObject({ code: "claim_too_early", deadline: late.json.entry.serverTime + DAY });
  });

  it("keeps resign, the draw answers and a late ending Move valid after the deadline", async () => {
    for (const kind of ["resign", "drawAccept", "drawDecline"]) {
      const game = await start({ daysPerMove: 1 });
      await play(game, ["e2e4"]);
      await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
      await patchMeta(game.gameId, (m) => (m.lastMoveAt -= 2 * DAY));
      expect((await append(game.gameId, game.black, { seq: 3, ply: 1, kind })).status, kind).toBe(201);
    }
    const game = await start({ daysPerMove: 1 });
    await play(game, ["f2f3", "e7e5", "g2g4"]);
    await patchMeta(game.gameId, (m) => (m.lastMoveAt -= 2 * DAY));
    const mate = await append(game.gameId, game.black, { seq: 4, ply: 4, kind: "move", uci: "d8h4", end: true });
    expect(mate.status).toBe(201);
    expect((await events(game.gameId, game.white)).json.status).toBe("closed");
  });
});

describe("the deadline in GET events", () => {
  it("gives the side to move's deadline, from startedAt and then from each Move", async () => {
    const game = await start({ daysPerMove: 3 });
    const before = await events(game.gameId, game.white);
    expect(before.json.deadline).toBe(before.json.startedAt + 3 * DAY);
    await play(game, ["e2e4"]);
    const after = await events(game.gameId, game.black);
    expect(after.json.deadline).toBe(after.json.entries[0].serverTime + 3 * DAY);
    // Draw offers don't move it.
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    expect((await events(game.gameId, game.black)).json.deadline).toBe(after.json.deadline);
  });

  it("is null while waiting and once the log is closed", async () => {
    const waiting = await create();
    expect((await events(waiting.gameId, waiting.seatSecret)).json.deadline).toBeNull();
    const game = await resigned();
    expect((await events(game.gameId, game.white)).json.deadline).toBeNull();
  });
});

describe("a Move that ends the Game (R2)", () => {
  it("closes the log", async () => {
    const game = await start();
    await play(game, ["f2f3", "e7e5", "g2g4"]);
    const mate = await append(game.gameId, game.black, { seq: 4, ply: 4, kind: "move", uci: "d8h4", end: true });
    expect(mate.status).toBe(201);
    expect(mate.json.entry.end).toBe(true);
    expect((await events(game.gameId, game.white)).json.status).toBe("closed");
    const after = await append(game.gameId, game.white, { seq: 5, ply: 4, kind: "resign" });
    expect(after.json.error.code).toBe("game_closed");
  });

  it("is part of the idempotent-retry comparison", async () => {
    const game = await start();
    await play(game, ["f2f3", "e7e5", "g2g4"]);
    const body = { seq: 4, ply: 4, kind: "move", uci: "d8h4", end: true };
    expect((await append(game.gameId, game.black, body)).status).toBe(201);
    expect((await append(game.gameId, game.black, body)).status).toBe(200);
    const without = await append(game.gameId, game.black, { seq: 4, ply: 4, kind: "move", uci: "d8h4" });
    expect(without.json.error.code).toBe("seq_conflict");

    const other = await start();
    await play(other, ["e2e4"]);
    await append(other.gameId, other.black, { seq: 2, ply: 2, kind: "move", uci: "e7e5" });
    const withEnd = await append(other.gameId, other.black, { seq: 2, ply: 2, kind: "move", uci: "e7e5", end: true });
    expect(withEnd.json.error.code).toBe("seq_conflict");
  });

  it("is only a Move's, and only true", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    for (const body of [
      { seq: 2, ply: 1, kind: "resign", end: true },
      { seq: 2, ply: 2, kind: "move", uci: "e7e5", end: false },
      { seq: 2, ply: 2, kind: "move", uci: "e7e5", end: "yes" },
    ]) {
      const res = await append(game.gameId, game.black, body as any);
      expect(res.status, JSON.stringify(body)).toBe(400);
      expect(res.json.error.code).toBe("bad_request");
    }
  });
});

describe("rematch", () => {
  /** White offers a rematch in a new Game where White takes Black; returns the new Game's creation. */
  async function offer(game: Started, seq = 4, ply = 2) {
    const next = await create("black", { invite: "token" });
    expect(next.joinToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(next.inviteCode).toBeUndefined();
    const res = await append(game.gameId, game.white, {
      seq,
      ply,
      kind: "rematchOffer",
      rematch: { gameId: next.gameId, joinToken: next.joinToken! },
    });
    expect(res.status).toBe(201);
    return next;
  }

  it("offer, join the new Game through the old log, accept, play", async () => {
    const game = await resigned();
    const next = await offer(game);

    // Black reads the offer from the old log and redeems the join token.
    const read = await events(game.gameId, game.black, 3);
    const offerEntry = read.json.entries[0];
    expect(offerEntry).toMatchObject({ kind: "rematchOffer", side: "white", rematch: { gameId: next.gameId } });
    expect(read.json.rematch).toEqual({ offeredBy: "white", gameId: next.gameId, answer: null });
    const joined = await join(offerEntry.rematch.gameId, offerEntry.rematch.joinToken);
    expect(joined.status).toBe(200);
    expect(joined.json.side).toBe("white");

    const accept = await append(game.gameId, game.black, { seq: 5, ply: 2, kind: "rematchAccept" });
    expect(accept.status).toBe(201);
    expect((await events(game.gameId, game.white)).json.rematch.answer).toBe("accept");

    // The new Game: the old Black is now White and moves first.
    const move = await append(next.gameId, joined.json.seatSecret, { seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(move.status).toBe(201);
    expect(move.json.entry.side).toBe("white");

    // The join token works once.
    expect((await join(next.gameId, next.joinToken!)).json.error.code).toBe("invite_used");
  });

  it("allows one offer per Game and one answer, from the other Seat only", async () => {
    const game = await resigned();
    await offer(game);
    const second = await create("white", { invite: "token" });
    const again = await append(game.gameId, game.black, {
      seq: 5,
      ply: 2,
      kind: "rematchOffer",
      rematch: { gameId: second.gameId, joinToken: second.joinToken! },
    });
    expect(again.json.error.code).toBe("rematch_already_offered");

    const selfAnswer = await append(game.gameId, game.white, { seq: 5, ply: 2, kind: "rematchAccept" });
    expect(selfAnswer.status).toBe(403);
    expect(selfAnswer.json.error.code).toBe("not_your_turn");

    expect((await append(game.gameId, game.black, { seq: 5, ply: 2, kind: "rematchDecline" })).status).toBe(201);
    const late = await append(game.gameId, game.black, { seq: 6, ply: 2, kind: "rematchAccept" });
    expect(late.json.error.code).toBe("rematch_answered");
    expect((await events(game.gameId, game.black)).json.rematch.answer).toBe("decline");
  });

  it("refuses an answer with no offer", async () => {
    const game = await resigned();
    const res = await append(game.gameId, game.white, { seq: 4, ply: 2, kind: "rematchAccept" });
    expect(res.json.error.code).toBe("no_rematch_offer");
  });

  it("refuses an offer on an open log (R2)", async () => {
    const game = await start();
    // Fool's mate sent without "end": the log stays open, so no rematch yet.
    await play(game, ["f2f3", "e7e5", "g2g4", "d8h4"]);
    const next = await create("black", { invite: "token" });
    const res = await append(game.gameId, game.white, {
      seq: 5,
      ply: 4,
      kind: "rematchOffer",
      rematch: { gameId: next.gameId, joinToken: next.joinToken! },
    });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("game_not_over");
    expect((await events(game.gameId, game.white)).json.status).toBe("active");
  });

  it("accepts an offer after a Move that ended the Game", async () => {
    const game = await start();
    await play(game, ["f2f3", "e7e5", "g2g4"]);
    await append(game.gameId, game.black, { seq: 4, ply: 4, kind: "move", uci: "d8h4", end: true });
    await offer(game, 5, 4);
    expect((await events(game.gameId, game.black)).json.rematch).toMatchObject({ offeredBy: "white", answer: null });
  });

  it("refuses a wrong join token", async () => {
    const next = await create("white", { invite: "token" });
    const res = await join(next.gameId, "B".repeat(43));
    expect(res.status).toBe(404);
    expect(res.json.error.code).toBe("invite_not_found");
  });

  it("refuses a wrong join token after the Game has started, as for any wrong token", async () => {
    const next = await create("white", { invite: "token" });
    expect((await join(next.gameId, next.joinToken!)).status).toBe(200);
    const wrong = await join(next.gameId, "B".repeat(43));
    expect(wrong.status).toBe(404);
    expect(wrong.json.error.code).toBe("invite_not_found");
    expect((await join(next.gameId, next.joinToken!)).json.error.code).toBe("invite_used");
  });

  it("does not redeem an Invite Code through the join endpoint, around the rate limit", async () => {
    const created = await create("white");
    const canonical = created.inviteCode!.replace("-", "");
    const res = await join(created.gameId, canonical);
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("bad_request");
    expect((await events(created.gameId, created.seatSecret)).json.status).toBe("waiting");
  });
});
