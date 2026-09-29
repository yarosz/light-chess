// Size limits: the 2,000-entry cap and its exempt kinds (R4), and the request body cap (R5).
import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { ORIGIN, append, call, create, digest, freshV6, patchMeta, play, start, type Started } from "./helpers";

const MAX = 2_000;

/** A Game whose log already holds 2,000 entries, White to move after 1.e4 e5 (seq and ply faked). */
async function full(patch: (meta: any) => void = () => {}): Promise<Started> {
  const game = await start();
  await play(game, ["e2e4", "e7e5"]);
  await patchMeta(game.gameId, (m) => {
    m.latestSeq = MAX;
    patch(m);
  });
  return game;
}

describe("the entry cap (R4)", () => {
  it("refuses a move, a drawOffer and a drawDecline past 2,000 entries", async () => {
    const game = await full();
    const move = await append(game.gameId, game.white, { seq: MAX + 1, ply: 3, kind: "move", uci: "g1f3" });
    expect(move.status).toBe(409);
    expect(move.json.error.code).toBe("log_full");
    const offer = await append(game.gameId, game.black, { seq: MAX + 1, ply: 2, kind: "drawOffer" });
    expect(offer.json.error.code).toBe("log_full");
    const offered = await full((m) => (m.openDrawBy = "black"));
    const decline = await append(offered.gameId, offered.white, { seq: MAX + 1, ply: 2, kind: "drawDecline" });
    expect(decline.json.error.code).toBe("log_full");
  });

  it("still takes resign, drawAccept and claim on a full log", async () => {
    const resign = await full();
    expect((await append(resign.gameId, resign.white, { seq: MAX + 1, ply: 2, kind: "resign" })).status).toBe(201);

    const accept = await full((m) => (m.openDrawBy = "black"));
    expect((await append(accept.gameId, accept.white, { seq: MAX + 1, ply: 2, kind: "drawAccept" })).status).toBe(201);

    const claim = await full((m) => (m.lastMoveAt -= 4 * 86_400_000));
    expect((await append(claim.gameId, claim.black, { seq: MAX + 1, ply: 2, kind: "claim" })).status).toBe(201);
  });

  it("still takes the rematch kinds after a terminal entry at the cap", async () => {
    const game = await full();
    await append(game.gameId, game.white, { seq: MAX + 1, ply: 2, kind: "resign" });
    const next = await create("black", { invite: "token" });
    const offer = await append(game.gameId, game.black, {
      seq: MAX + 2,
      ply: 2,
      kind: "rematchOffer",
      rematch: { gameId: next.gameId, joinToken: next.joinToken! },
    });
    expect(offer.status).toBe(201);
    expect((await append(game.gameId, game.white, { seq: MAX + 3, ply: 2, kind: "rematchAccept" })).status).toBe(201);
  });
});

describe("the body cap (R5)", () => {
  const oversized = () => JSON.stringify({ v: "1.0", side: "white", daysPerMove: 3, pad: "x".repeat(4_096) });

  it("refuses a body whose Content-Length is over 4,096 bytes", async () => {
    const res = await SELF.fetch(`${ORIGIN}/v1/games`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": freshV6() },
      body: oversized(),
    });
    expect(res.status).toBe(413);
    expect(((await res.json()) as any).error.code).toBe("body_too_large");
  });

  it("refuses a chunked body once more than 4,096 bytes have been read", async () => {
    const bytes = new TextEncoder().encode(oversized());
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (let i = 0; i < bytes.length; i += 512) controller.enqueue(bytes.slice(i, i + 512));
        controller.close();
      },
    });
    const res = await SELF.fetch(`${ORIGIN}/v1/games`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": freshV6() },
      body: stream,
    });
    expect(res.status).toBe(413);
    expect(((await res.json()) as any).error.code).toBe("body_too_large");
  });

  it("applies to appends, redeems, joins and sync", async () => {
    const game = await start();
    const pad = "x".repeat(4_096);
    const paths = [
      { path: `/v1/games/${game.gameId}/events`, body: { v: "1.0", seq: 1, ply: 1, kind: "move", uci: "e2e4", hash: digest(1), pad } },
      { path: "/v1/invites/ABCD-EFGH/redeem", body: { v: "1.0", pad } },
      { path: `/v1/games/${game.gameId}/join`, body: { v: "1.0", joinToken: "B".repeat(43), pad } },
      { path: "/v1/sync", body: { v: "1.0", games: [], pad } },
    ];
    for (const { path, body } of paths) {
      const res = await call("POST", path, { secret: game.white, body, ip: "203.0.113.9" });
      expect(res.status, path).toBe(413);
      expect(res.json.error.code).toBe("body_too_large");
    }
  });

  it("accepts a body of exactly 4,096 bytes", async () => {
    const base = JSON.stringify({ v: "1.0", side: "white", daysPerMove: 3, pad: "" });
    const body = JSON.stringify({ v: "1.0", side: "white", daysPerMove: 3, pad: "x".repeat(4_096 - base.length) });
    expect(new TextEncoder().encode(body).length).toBe(4_096);
    const res = await SELF.fetch(`${ORIGIN}/v1/games`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": freshV6() },
      body,
    });
    expect(res.status).toBe(201);
    await res.arrayBuffer();
  });
});
