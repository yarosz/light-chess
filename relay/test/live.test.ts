// Presence over WebSocket Hibernation: "Live" needs both Seats here; gone after 10 s (G3).
import { SELF, runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { ORIGIN, append, call, create, patchMeta, start, stub } from "./helpers";

/** A client socket that queues what the Relay sends. */
class Client {
  private messages: any[] = [];
  private waiters: (() => void)[] = [];
  closeCode: number | null = null;

  constructor(readonly ws: WebSocket) {
    ws.addEventListener("message", (event) => {
      this.messages.push(JSON.parse(event.data as string));
      this.waiters.splice(0).forEach((wake) => wake());
    });
    ws.addEventListener("close", (event) => {
      this.closeCode = event.code;
      this.waiters.splice(0).forEach((wake) => wake());
    });
  }

  /** The next message matching [predicate], skipping others. */
  async next(predicate: (m: any) => boolean = () => true): Promise<any> {
    for (;;) {
      const i = this.messages.findIndex(predicate);
      if (i >= 0) return this.messages.splice(i, 1)[0];
      await new Promise<void>((wake) => this.waiters.push(wake));
    }
  }

  async closed(): Promise<number> {
    while (this.closeCode === null) await new Promise<void>((wake) => this.waiters.push(wake));
    return this.closeCode;
  }

  /** Pings and returns the presence answer, dropping older presence messages. */
  async ping(): Promise<any> {
    this.messages = this.messages.filter((m) => m.type !== "presence");
    this.ws.send(JSON.stringify({ type: "ping" }));
    return this.next((m) => m.type === "presence");
  }
}

async function connect(gameId: string, secret: string): Promise<Client> {
  const res = await SELF.fetch(`${ORIGIN}/v1/games/${gameId}/live`, {
    headers: { Upgrade: "websocket", Authorization: `Bearer ${secret}` },
  });
  expect(res.status).toBe(101);
  const ws = res.webSocket!;
  const client = new Client(ws);
  ws.accept();
  return client;
}

describe("live presence", () => {
  it("is Live only once both Seats are here", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    expect(await white.next()).toMatchObject({ type: "presence", live: false, opponent: "gone" });

    const black = await connect(game.gameId, game.black);
    expect(await black.next()).toMatchObject({ type: "presence", live: true, opponent: "here" });
    expect(await white.next()).toMatchObject({ type: "presence", live: true, opponent: "here" });
    expect(await white.ping()).toMatchObject({ live: true, opponent: "here" });
  });

  it("reports the opponent gone after 10 s without a ping", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    const black = await connect(game.gameId, game.black);
    expect(await white.ping()).toMatchObject({ live: true });

    // Black's phone went into a drawer: its last ping is now 10 s old.
    await runInDurableObject(stub(game.gameId), (_i, state) => {
      for (const ws of state.getWebSockets("black")) {
        ws.serializeAttachment({ ...ws.deserializeAttachment(), lastSeen: Date.now() - 10_000 });
      }
    });
    expect(await white.ping()).toMatchObject({ live: false, opponent: "gone" });
    // Black pings again and is back.
    expect(await black.ping()).toMatchObject({ live: true, opponent: "here" });
    expect(await white.ping()).toMatchObject({ live: true, opponent: "here" });
  });

  it("tells the other Seat when a socket closes", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    const black = await connect(game.gameId, game.black);
    await white.next((m) => m.live === true);
    black.ws.close(1000, "left");
    expect(await white.next((m) => m.type === "presence" && m.live === false)).toMatchObject({ opponent: "gone" });
  });

  it("pushes every appended entry to both sockets", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    const black = await connect(game.gameId, game.black);
    await append(game.gameId, game.white, { seq: 1, ply: 1, kind: "move", uci: "e2e4" });
    for (const client of [white, black]) {
      expect(await client.next((m) => m.type === "entry")).toMatchObject({ entry: { seq: 1, uci: "e2e4", side: "white" } });
    }
  });

  it("keeps one socket per Seat: a new one replaces the old", async () => {
    const game = await start();
    const first = await connect(game.gameId, game.white);
    await connect(game.gameId, game.white);
    expect(await first.closed()).toBe(4001);
  });

  it("closes the sockets when the Game is deleted", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    await patchMeta(game.gameId, (m) => (m.deleteAt = Date.now() - 1));
    expect(await runDurableObjectAlarm(stub(game.gameId))).toBe(true);
    expect(await white.closed()).toBe(4004);
  });

  it("answers anything but a ping with an error", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    white.ws.send("hello");
    expect(await white.next((m) => m.type === "error")).toEqual({ type: "error", code: "bad_request" });
  });

  it("answers a message over 256 bytes with an error, even a ping (R5)", async () => {
    const game = await start();
    const white = await connect(game.gameId, game.white);
    await white.next((m) => m.type === "presence");
    const base = JSON.stringify({ type: "ping", pad: "" });
    white.ws.send(JSON.stringify({ type: "ping", pad: "x".repeat(257 - base.length) }));
    expect(await white.next()).toEqual({ type: "error", code: "bad_request" });
    white.ws.send(JSON.stringify({ type: "ping", pad: "x".repeat(256 - base.length) }));
    expect(await white.next()).toMatchObject({ type: "presence" });
  });

  it("refuses a bad secret, an unstarted Game and a plain GET", async () => {
    const game = await start();
    const bad = await SELF.fetch(`${ORIGIN}/v1/games/${game.gameId}/live`, {
      headers: { Upgrade: "websocket", Authorization: `Bearer ${"E".repeat(43)}` },
    });
    expect(bad.status).toBe(401);
    const waiting = await create();
    const early = await SELF.fetch(`${ORIGIN}/v1/games/${waiting.gameId}/live`, {
      headers: { Upgrade: "websocket", Authorization: `Bearer ${waiting.seatSecret}` },
    });
    expect(early.status).toBe(409);
    const plain = await call("GET", `/v1/games/${game.gameId}/live`, { secret: game.white });
    expect(plain.status).toBe(426);
    expect(plain.json.error.code).toBe("upgrade_required");
  });
});
