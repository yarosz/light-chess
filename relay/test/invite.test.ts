// Invite Codes: single use, 48 h, cancel vs redeem (C3, G2), and the per-IP redeem limit (F11).
import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { sha256Hex } from "../src/secrets";
import { call, create, events, freshIp, oneWindow, patchMeta, redeem, stub, tableCount } from "./helpers";

const cancel = (gameId: string, secret: string) => call("DELETE", `/v1/games/${gameId}/invite`, { secret });

describe("Invite Codes", () => {
  it("work once", async () => {
    const created = await create();
    expect((await redeem(created.inviteCode!)).status).toBe(200);
    const again = await redeem(created.inviteCode!);
    expect(again.status).toBe(409);
    expect(again.json.error.code).toBe("invite_used");
  });

  it("read in any case, without the hyphen, with O for 0 and I or L for 1", async () => {
    const created = await create();
    const typed = created.inviteCode!.replace("-", " ").toLowerCase().replace(/0/g, "o").replace(/1/g, "l");
    const res = await redeem(typed);
    expect(res.status).toBe(200);
    expect(res.json.gameId).toBe(created.gameId);
  });

  it("refuse a code that can't be one", async () => {
    for (const code of ["ABCD-EFG", "ABCD-EFGU", "ABCD-EFGH-J"]) {
      const res = await redeem(code);
      expect(res.status, code).toBe(400);
      expect(res.json.error.code).toBe("bad_request");
    }
  });

  it("refuse an unknown code, and the guess leaves no storage behind", async () => {
    const res = await redeem("ZZZZ-ZZZZ");
    expect(res.status).toBe(404);
    expect(res.json.error.code).toBe("invite_not_found");
    const probed = env.GAME.idFromName("invite:ZZZZZZZZ");
    const tables = await runInDurableObject(env.GAME.get(probed), (_i, state) =>
      state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('meta', 'entries')").toArray(),
    );
    expect(tables).toEqual([]);
  });

  it("expire 48 h after creation, and the expired Game is gone", async () => {
    const created = await create();
    await patchMeta(created.gameId, (m) => (m.invite.expiresAt = Date.now() - 1));
    const res = await redeem(created.inviteCode!);
    expect(res.status).toBe(404);
    expect(res.json.error.code).toBe("invite_not_found");
    expect(await tableCount(created.gameId)).toBe(0);
    expect((await events(created.gameId, created.seatSecret)).json.error.code).toBe("game_not_found");
  });

  it("refuse a redeem whose major is not the Game's", async () => {
    const created = await create();
    await patchMeta(created.gameId, (m) => (m.v = "2.0"));
    const res = await redeem(created.inviteCode!, "1.0");
    expect(res.status).toBe(409);
    expect(res.json.error).toMatchObject({ code: "version_mismatch", gameV: "2.0" });
  });
});

describe("cancel", () => {
  it("deletes an open invite's Game, and the code no longer redeems", async () => {
    const created = await create();
    const res = await cancel(created.gameId, created.seatSecret);
    expect(res.status).toBe(200);
    expect(res.json).toEqual({ cancelled: true, gameId: created.gameId });
    expect((await redeem(created.inviteCode!)).json.error.code).toBe("invite_not_found");
    expect((await cancel(created.gameId, created.seatSecret)).json.error.code).toBe("game_not_found");
  });

  it("comes too late once the invite is redeemed", async () => {
    const created = await create();
    await redeem(created.inviteCode!);
    const res = await cancel(created.gameId, created.seatSecret);
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("invite_redeemed");
  });

  it("needs the creator's seat secret", async () => {
    const created = await create();
    const joined = await redeem(created.inviteCode!);
    expect((await cancel(created.gameId, joined.json.seatSecret)).status).toBe(401);
    const other = await create();
    expect((await cancel(other.gameId, "C".repeat(43))).json.error.code).toBe("bad_seat_secret");
  });

  it("races redeem, and exactly one wins", async () => {
    for (let i = 0; i < 10; i++) {
      const created = await create();
      const [cancelled, redeemed] = await Promise.all([cancel(created.gameId, created.seatSecret), redeem(created.inviteCode!)]);
      const outcome = `${cancelled.status}/${redeemed.status}`;
      // Either the cancel won (the redeem finds nothing) or the redeem won (the cancel is too late).
      expect(["200/404", "409/200"]).toContain(outcome);
      const read = await events(created.gameId, created.seatSecret);
      expect(read.status).toBe(outcome === "200/404" ? 404 : 200);
    }
  });
});

describe("cancel and redeem inside one Durable Object", () => {
  // Both RPCs start in the same tick, so any await between an operation's read and its write lets
  // the other interleave. Exactly one may win.
  for (const first of ["redeem", "cancel"] as const) {
    it(`let exactly one win when ${first} starts first`, async () => {
      const created = await create();
      const creatorHash = await sha256Hex(created.seatSecret);
      const codeHash = await sha256Hex(created.inviteCode!.replace("-", ""));
      const joinerHash = await sha256Hex("J".repeat(43));
      const outcome = await runInDurableObject(stub(created.gameId), async (game) => {
        const redeemIt = () => game.redeem("1.0", codeHash, joinerHash);
        const cancelIt = () => game.cancel(creatorHash);
        const [redeemed, cancelled] =
          first === "redeem"
            ? await Promise.all([redeemIt(), cancelIt()])
            : await Promise.all([cancelIt(), redeemIt()]).then(([c, r]) => [r, c] as const);
        return `${cancelled.status}/${redeemed.status}`;
      });
      expect(["200/404", "409/200"]).toContain(outcome);
    });
  }
});

describe("the redeem rate limit", () => {
  it("allows 10 redemptions a minute per IP, then answers 429 with Retry-After", async () => {
    await oneWindow();
    const ip = freshIp();
    const statuses: number[] = [];
    for (let i = 0; i < 11; i++) {
      statuses.push((await call("POST", "/v1/invites/ZZZZ-ZZZ0/redeem", { body: { v: "1.0", seatSecret: "S".repeat(43) }, ip })).status);
    }
    expect(statuses.slice(0, 10)).toEqual(Array(10).fill(404));
    expect(statuses[10]).toBe(429);
    const limited = await call("POST", "/v1/invites/ZZZZ-ZZZ0/redeem", { body: { v: "1.0", seatSecret: "S".repeat(43) }, ip });
    expect(limited.json.error.code).toBe("rate_limited");
    expect(limited.headers.get("Retry-After")).toBe("60");

    // Another address is unaffected, and so is a valid code from it.
    const created = await create();
    expect((await redeem(created.inviteCode!)).status).toBe(200);
  });

  it("counts malformed codes too", async () => {
    await oneWindow();
    const ip = freshIp();
    for (let i = 0; i < 10; i++) await call("POST", "/v1/invites/x/redeem", { body: { v: "1.0", seatSecret: "S".repeat(43) }, ip });
    expect((await call("POST", "/v1/invites/x/redeem", { body: { v: "1.0", seatSecret: "S".repeat(43) }, ip })).status).toBe(429);
  });
});
