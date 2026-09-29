// The batched sync (E7) and the 30-day deletion alarm (C6).
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { append, call, create, events, patchMeta, play, start, stub, tableCount } from "./helpers";

const DAY = 86_400_000;
const sync = (games: unknown[], v = "1.0") => call("POST", "/v1/sync", { body: { v, games } });

describe("batched sync", () => {
  it("returns each Game's entries since its cursor, in request order", async () => {
    const a = await start();
    const b = await start();
    await play(a, ["e2e4", "e7e5", "g1f3"]);
    await play(b, ["d2d4"]);
    const res = await sync([
      { gameId: a.gameId, seatSecret: a.black, since: 1 },
      { gameId: b.gameId, seatSecret: b.white },
    ]);
    expect(res.status).toBe(200);
    const [ra, rb] = res.json.results;
    expect(ra).toMatchObject({ gameId: a.gameId, ok: true, game: { side: "black", latestSeq: 3 } });
    expect(ra.game.entries.map((e: any) => e.uci)).toEqual(["e7e5", "g1f3"]);
    expect(rb).toMatchObject({ gameId: b.gameId, ok: true, game: { side: "white", latestSeq: 1 } });
    expect(rb.game.entries).toHaveLength(1);
  });

  it("reports one Game's error without failing the batch", async () => {
    const a = await start();
    const waiting = await create();
    const res = await sync([
      { gameId: a.gameId, seatSecret: "D".repeat(43), since: 0 },
      { gameId: "0".repeat(64), seatSecret: a.white, since: 0 },
      { gameId: "not-a-game", seatSecret: a.white, since: 0 },
      { gameId: waiting.gameId, seatSecret: waiting.seatSecret, since: 0 },
      { gameId: a.gameId, seatSecret: a.white, since: 0 },
    ]);
    const results = res.json.results;
    expect(results.map((r: any) => r.ok)).toEqual([false, false, false, true, true]);
    expect(results[0].error.code).toBe("bad_seat_secret");
    expect(results[1].error.code).toBe("game_not_found");
    expect(results[2].error.code).toBe("game_not_found");
    expect(results[3].game.status).toBe("waiting");
  });

  it("takes 1 to 5 Games", async () => {
    const a = await start();
    const six = Array(6).fill({ gameId: a.gameId, seatSecret: a.white, since: 0 });
    expect((await sync(six)).json.error.code).toBe("bad_request");
    expect((await sync([])).json.error.code).toBe("bad_request");
    expect((await sync(six.slice(0, 5))).status).toBe(200);
  });

  it("refuses an unsupported major", async () => {
    const res = await sync([], "3.0");
    expect(res.json.error.code).toBe("unsupported_version");
  });
});

describe("retention alarm", () => {
  const alarmOf = (gameId: string) => runInDurableObject(stub(gameId), (_i, state) => state.storage.getAlarm());

  it("is set to the invite's expiry, then 30 days after the start, then 30 days after each entry", async () => {
    const before = Date.now();
    const created = await create();
    expect(await alarmOf(created.gameId)).toBe(created.inviteExpiresAt);

    const game = await start();
    const afterStart = (await alarmOf(game.gameId))!;
    expect(afterStart).toBeGreaterThanOrEqual(before + 30 * DAY);
    expect(afterStart).toBeLessThanOrEqual(Date.now() + 30 * DAY);

    await play(game, ["e2e4"]);
    const entryTime = (await events(game.gameId, game.white)).json.entries[0].serverTime;
    expect(await alarmOf(game.gameId)).toBe(entryTime + 30 * DAY);
  });

  it("deletes a Game 30 days after its last entry", async () => {
    const game = await start();
    await play(game, ["e2e4", "e7e5"]);
    await patchMeta(game.gameId, (m) => (m.deleteAt = Date.now() - 1));
    expect(await runDurableObjectAlarm(stub(game.gameId))).toBe(true);
    expect(await tableCount(game.gameId)).toBe(0);
    expect((await events(game.gameId, game.white)).json.error.code).toBe("game_not_found");
    const res = await append(game.gameId, game.white, { seq: 3, ply: 3, kind: "move", uci: "g1f3" });
    expect(res.json.error.code).toBe("game_not_found");
  });

  it("deletes a Game whose invite expired unredeemed", async () => {
    const created = await create();
    await patchMeta(created.gameId, (m) => (m.deleteAt = m.invite.expiresAt = Date.now() - 1));
    expect(await runDurableObjectAlarm(stub(created.gameId))).toBe(true);
    expect(await tableCount(created.gameId)).toBe(0);
  });

  it("keeps a Game that is not yet due, and re-arms the alarm", async () => {
    const game = await start();
    await patchMeta(game.gameId, (m) => (m.deleteAt = Date.now() + DAY));
    // Pretend the alarm fired early (for example after the deadline was moved by an append).
    expect(await runDurableObjectAlarm(stub(game.gameId))).toBe(true);
    expect(await tableCount(game.gameId)).toBe(2);
    expect(await alarmOf(game.gameId)).toBeGreaterThan(Date.now());
  });
});
