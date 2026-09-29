// The phone chooses its own seat secret on redeem and join (W9), so a lost response is retried safely.
import { describe, expect, it } from "vitest";
import { newToken } from "../src/secrets";
import { append, call, create, digest, events, freshIp, join, patchMeta, redeem, start } from "./helpers";

async function rematchOffer(): Promise<{ gameId: string; joinToken: string }> {
  const game = await start();
  expect((await append(game.gameId, game.white, { seq: 1, ply: 0, kind: "resign" })).status).toBe(201);
  const next = await create("white", { invite: "token" });
  const offer = await append(game.gameId, game.black, {
    seq: 2,
    ply: 0,
    kind: "rematchOffer",
    rematch: { gameId: next.gameId, joinToken: next.joinToken! },
  });
  expect(offer.status).toBe(201);
  return { gameId: next.gameId, joinToken: next.joinToken! };
}

describe("a redeem with the phone's own seat secret", () => {
  it("is idempotent: a retry with the same secret after a lost response takes the same Seat", async () => {
    const created = await create();
    const secret = newToken();
    const first = await redeem(created.inviteCode!, "1.0", secret);
    expect(first.status).toBe(200);
    // Time passes and the Game goes on before the retry arrives.
    const moved = await append(created.gameId, created.seatSecret, { seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    expect(moved.status).toBe(201);
    const retry = await redeem(created.inviteCode!, "1.0", secret);
    expect(retry.status).toBe(200);
    expect(retry.json).toMatchObject({ gameId: first.json.gameId, side: "black", daysPerMove: 3, startedAt: first.json.startedAt });
    // The retried Seat acts with the same secret.
    const read = await events(created.gameId, secret);
    expect(read.status).toBe(200);
    expect(read.json.side).toBe("black");
    expect(read.json.latestSeq).toBe(1);
    const reply = await append(created.gameId, secret, { seq: 2, ply: 2, kind: "move", uci: "e7e5" });
    expect(reply.status).toBe(201);
  });

  it("refuses the same code with another secret as used", async () => {
    const created = await create();
    expect((await redeem(created.inviteCode!, "1.0", newToken())).status).toBe(200);
    const other = await redeem(created.inviteCode!, "1.0", newToken());
    expect(other.status).toBe(409);
    expect(other.json.error.code).toBe("invite_used");
  });

  it("never returns the secret", async () => {
    const created = await create();
    const res = await call("POST", `/v1/invites/${created.inviteCode}/redeem`, {
      body: { v: "1.0", seatSecret: newToken() },
      ip: freshIp(),
    });
    expect(res.status).toBe(200);
    expect(res.json.seatSecret).toBeUndefined();
  });

  it("needs a seat secret of 43 base64url characters", async () => {
    const created = await create();
    for (const seatSecret of [undefined, "short", "=".repeat(43), 43]) {
      const res = await call("POST", `/v1/invites/${created.inviteCode}/redeem`, { body: { v: "1.0", seatSecret }, ip: freshIp() });
      expect(res.status, String(seatSecret)).toBe(400);
      expect(res.json.error.code).toBe("bad_request");
    }
    expect((await redeem(created.inviteCode!)).status).toBe(200);
  });

  it("refuses the creator's own secret, so the two Seats never share one", async () => {
    const created = await create();
    const res = await redeem(created.inviteCode!, "1.0", created.seatSecret);
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("bad_request");
    expect((await redeem(created.inviteCode!)).status).toBe(200);
  });

  it("still finds nothing once the Game is gone", async () => {
    const created = await create();
    const secret = newToken();
    expect((await redeem(created.inviteCode!, "1.0", secret)).status).toBe(200);
    await patchMeta(created.gameId, (m) => (m.usedInviteHash = digest(9)));
    const res = await redeem(created.inviteCode!, "1.0", secret);
    expect(res.status).toBe(404);
    expect(res.json.error.code).toBe("invite_not_found");
  });
});

describe("a join with the phone's own seat secret", () => {
  it("is idempotent with the same secret, and refused as used with another", async () => {
    const offer = await rematchOffer();
    const secret = newToken();
    const first = await join(offer.gameId, offer.joinToken, secret);
    expect(first.status).toBe(200);
    const retry = await join(offer.gameId, offer.joinToken, secret);
    expect(retry.status).toBe(200);
    expect(retry.json).toMatchObject({ gameId: offer.gameId, side: first.json.side, startedAt: first.json.startedAt });
    const other = await join(offer.gameId, offer.joinToken, newToken());
    expect(other.status).toBe(409);
    expect(other.json.error.code).toBe("invite_used");
    expect((await events(offer.gameId, secret)).json.side).toBe("black");
  });

  it("needs a seat secret", async () => {
    const offer = await rematchOffer();
    const res = await call("POST", `/v1/games/${offer.gameId}/join`, { body: { v: "1.0", joinToken: offer.joinToken } });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("bad_request");
  });
});
