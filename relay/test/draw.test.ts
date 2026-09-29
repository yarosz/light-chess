// Draw offers: the Relay tracks the open offer from kinds alone (R1).
import { describe, expect, it } from "vitest";
import { append, events, play, start } from "./helpers";

describe("draw offers", () => {
  it("lets only the side that just moved offer, and reports the open offer", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    const byMover = await append(game.gameId, game.black, { seq: 2, ply: 1, kind: "drawOffer" });
    expect(byMover.status).toBe(403);
    expect(byMover.json.error.code).toBe("not_your_turn");
    expect((await events(game.gameId, game.black)).json.openDrawBy).toBeNull();
    expect((await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" })).status).toBe(201);
    expect((await events(game.gameId, game.black)).json.openDrawBy).toBe("white");
  });

  it("refuses an offer before the first Move", async () => {
    const game = await start();
    for (const secret of [game.white, game.black]) {
      const res = await append(game.gameId, secret, { seq: 1, ply: 0, kind: "drawOffer" });
      expect(res.status).toBe(403);
      expect(res.json.error.code).toBe("not_your_turn");
    }
  });

  it("refuses a second offer while one is open", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    const again = await append(game.gameId, game.white, { seq: 3, ply: 1, kind: "drawOffer" });
    expect(again.status).toBe(409);
    expect(again.json.error.code).toBe("draw_already_offered");
  });

  it("refuses an answer with no open offer, or from the offering side", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    for (const kind of ["drawAccept", "drawDecline"]) {
      const none = await append(game.gameId, game.black, { seq: 2, ply: 1, kind });
      expect(none.status, kind).toBe(409);
      expect(none.json.error.code).toBe("no_draw_offer");
    }
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    for (const kind of ["drawAccept", "drawDecline"]) {
      const self = await append(game.gameId, game.white, { seq: 3, ply: 1, kind });
      expect(self.status, kind).toBe(409);
      expect(self.json.error.code).toBe("no_draw_offer");
    }
  });

  it("clears the offer on a decline", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    expect((await append(game.gameId, game.black, { seq: 3, ply: 1, kind: "drawDecline" })).status).toBe(201);
    expect((await events(game.gameId, game.white)).json.openDrawBy).toBeNull();
    const accept = await append(game.gameId, game.black, { seq: 4, ply: 1, kind: "drawAccept" });
    expect(accept.json.error.code).toBe("no_draw_offer");
  });

  it("clears the offer on a Move, which refuses it implicitly", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    expect((await append(game.gameId, game.black, { seq: 3, ply: 2, kind: "move", uci: "e7e5" })).status).toBe(201);
    expect((await events(game.gameId, game.white)).json.openDrawBy).toBeNull();
    const accept = await append(game.gameId, game.white, { seq: 4, ply: 2, kind: "drawAccept" });
    expect(accept.json.error.code).toBe("no_draw_offer");
    // Black has just moved, so Black may now offer.
    expect((await append(game.gameId, game.black, { seq: 4, ply: 2, kind: "drawOffer" })).status).toBe(201);
    expect((await events(game.gameId, game.white)).json.openDrawBy).toBe("black");
  });

  it("closes the log on an accepted offer", async () => {
    const game = await start();
    await play(game, ["e2e4"]);
    await append(game.gameId, game.white, { seq: 2, ply: 1, kind: "drawOffer" });
    expect((await append(game.gameId, game.black, { seq: 3, ply: 1, kind: "drawAccept" })).status).toBe(201);
    const read = await events(game.gameId, game.white);
    expect(read.json.status).toBe("closed");
    expect(read.json.openDrawBy).toBeNull();
  });
});
