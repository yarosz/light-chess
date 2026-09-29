// What the Worker checks before routing: the rate-limit key (L1), the create limit (L2), plain HTTP
// refused (L3), and the shape of a sync's seat secrets.
import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { UNKNOWN_CLIENT, clientKey, isRefusedPlainHttp } from "../src/limits";
import { call, create, freshIp, freshV6, start } from "./helpers";

describe("the rate-limit key (L1)", () => {
  it("keeps an IPv4 address whole", () => {
    expect(clientKey("203.0.113.7")).toBe("203.0.113.7");
    expect(clientKey(" 203.0.113.7 ")).toBe("203.0.113.7");
  });

  it("keys an IPv6 address on its /64, however it is written", () => {
    const key = "2001:db8:0:0::/64";
    expect(clientKey("2001:db8::1")).toBe(key);
    expect(clientKey("2001:db8::")).toBe(key);
    expect(clientKey("2001:0db8:0000:0000:ffff:ffff:ffff:ffff")).toBe(key);
    expect(clientKey("2001:DB8::ABCD")).toBe(key);
    expect(clientKey("2001:db8:0:0:1:2:3:4")).toBe(key);
    expect(clientKey("2001:db8:0:1::1")).toBe("2001:db8:0:1::/64");
    expect(clientKey("2001:db8:a:b:c:d:e:f")).toBe("2001:db8:a:b::/64");
    expect(clientKey("fe80:0:0:0:1::")).toBe("fe80:0:0:0::/64");
  });

  it("takes the loopback and the unspecified address", () => {
    expect(clientKey("::1")).toBe("0:0:0:0::/64");
    expect(clientKey("::")).toBe("0:0:0:0::/64");
  });

  it("reads an IPv4-mapped IPv6 address as its IPv4 address", () => {
    expect(clientKey("::ffff:1.2.3.4")).toBe("1.2.3.4");
    expect(clientKey("::FFFF:1.2.3.4")).toBe("1.2.3.4");
    expect(clientKey("0:0:0:0:0:ffff:0102:0304")).toBe("1.2.3.4");
    expect(clientKey("::ffff:102:304")).toBe("1.2.3.4");
    // Another embedded IPv4 (NAT64) is an ordinary IPv6 address.
    expect(clientKey("64:ff9b::1.2.3.4")).toBe("64:ff9b:0:0::/64");
  });

  it("gives every missing or garbled address one shared key", () => {
    for (const header of [null, "", "unknown", "1.2.3", "1.2.3.256", "01.2.3.4", "2001:db8::1::2", "2001:db8:1:2:3:4:5:6:7",
      "2001:db8:1:2:3:4:5", "2001:db8::12345", "2001:db8::g", ":2001:db8::1", "::ffff:1.2.3.999", "fe80::1%eth0", "1.2.3.4:80"]) {
      expect(clientKey(header), String(header)).toBe(UNKNOWN_CLIENT);
    }
  });
});

describe("the create limit (L2)", () => {
  const post = (ip: string) => call("POST", "/v1/games", { body: { v: "1.0", side: "white", daysPerMove: 3 }, ip });

  it("allows 10 Games a minute per address, then answers 429 with Retry-After", async () => {
    const ip = freshIp();
    const statuses: number[] = [];
    for (let i = 0; i < 10; i++) statuses.push((await post(ip)).status);
    expect(statuses).toEqual(Array(10).fill(201));
    const limited = await post(ip);
    expect(limited.status).toBe(429);
    expect(limited.json.error.code).toBe("rate_limited");
    expect(limited.headers.get("Retry-After")).toBe("60");
    // Another address is unaffected.
    expect((await post(freshIp())).status).toBe(201);
  });

  it("counts every address of one IPv6 /64 together, and no other /64", async () => {
    const base = freshV6().replace(/::1$/, "");
    for (let i = 1; i <= 10; i++) expect((await post(`${base}::${i.toString(16)}`)).status).toBe(201);
    expect((await post(`${base}:ffff:ffff:ffff:ffff`)).status).toBe(429);
    expect((await post(freshV6())).status).toBe(201);
  });

  it("counts malformed bodies too", async () => {
    const ip = freshIp();
    for (let i = 0; i < 10; i++) {
      expect((await call("POST", "/v1/games", { body: { v: "1.0" }, ip })).status).toBe(400);
    }
    expect((await post(ip)).status).toBe(429);
  });

  it("puts requests without a readable address under one shared key", async () => {
    const statuses: number[] = [];
    for (let i = 0; i < 11; i++) {
      const res = await SELF.fetch("https://relay.test/v1/games", { method: "POST", body: "{}" });
      statuses.push(res.status);
      await res.arrayBuffer();
    }
    expect(statuses.slice(0, 10)).toEqual(Array(10).fill(400));
    expect(statuses[10]).toBe(429);
    expect((await call("POST", "/v1/games", { body: {}, ip: "not an address" })).status).toBe(429);
  });
});

describe("the redeem limit, keyed the same way (F11, L1)", () => {
  it("counts every address of one IPv6 /64 together", async () => {
    const base = freshV6().replace(/::1$/, "");
    const redeem = (ip: string) =>
      call("POST", "/v1/invites/ZZZZ-ZZZ0/redeem", { body: { v: "1.0", seatSecret: "S".repeat(43) }, ip });
    for (let i = 1; i <= 10; i++) expect((await redeem(`${base}::${i.toString(16)}`)).status).toBe(404);
    const limited = await redeem(`${base}:1:2:3:4`);
    expect(limited.status).toBe(429);
    expect(limited.headers.get("Retry-After")).toBe("60");
  });
});

describe("plain HTTP (L3)", () => {
  it("is refused on every endpoint with 426 upgrade_required, never redirected", async () => {
    for (const [method, path] of [["GET", "/health"], ["POST", "/v1/games"], ["POST", "/v1/sync"], ["GET", "/nowhere"]] as const) {
      const res = await SELF.fetch(`http://chess-relay.example${path}`, {
        method,
        redirect: "manual",
        headers: { "CF-Connecting-IP": freshIp() },
        body: method === "POST" ? JSON.stringify({ v: "1.0", side: "white", daysPerMove: 3 }) : undefined,
      });
      expect(res.status, path).toBe(426);
      expect(res.headers.get("Location")).toBeNull();
      const json = (await res.json()) as any;
      expect(json.error.code).toBe("upgrade_required");
      expect(json.error.message).toContain(`https://chess-relay.example${path}`);
    }
  });

  it("does not count against the create limit", async () => {
    const ip = freshIp();
    for (let i = 0; i < 11; i++) {
      const res = await SELF.fetch("http://chess-relay.example/v1/games", { method: "POST", headers: { "CF-Connecting-IP": ip }, body: "{}" });
      expect(res.status).toBe(426);
      await res.arrayBuffer();
    }
    expect((await call("POST", "/v1/games", { body: { v: "1.0", side: "white", daysPerMove: 3 }, ip })).status).toBe(201);
  });

  it("is served to a local wrangler dev: localhost, loopback and the emulator's host alias", async () => {
    for (const origin of ["http://localhost:8787", "http://127.0.0.1:8787", "http://[::1]:8787", "http://10.0.2.2:8787", "http://localhost"]) {
      const res = await SELF.fetch(`${origin}/health`);
      expect(res.status, origin).toBe(200);
      await res.arrayBuffer();
    }
  });

  it("is told apart by scheme and host alone", () => {
    expect(isRefusedPlainHttp(new URL("http://chess-relay.example/health"))).toBe(true);
    expect(isRefusedPlainHttp(new URL("http://10.0.2.3:8787/health"))).toBe(true);
    expect(isRefusedPlainHttp(new URL("http://localhost.example/health"))).toBe(true);
    expect(isRefusedPlainHttp(new URL("https://chess-relay.example/health"))).toBe(false);
    expect(isRefusedPlainHttp(new URL("HTTP://LOCALHOST:8787/health"))).toBe(false);
  });
});

describe("a sync's seat secrets", () => {
  it("must each be 43 base64url characters", async () => {
    const game = await start();
    const created = await create();
    for (const seatSecret of ["", "short", "S".repeat(44), "S".repeat(42) + "=", "S".repeat(42) + "+"]) {
      const res = await call("POST", "/v1/sync", {
        body: { v: "1.0", games: [{ gameId: game.gameId, seatSecret: game.white }, { gameId: created.gameId, seatSecret }] },
      });
      expect(res.status, seatSecret).toBe(400);
      expect(res.json.error.code).toBe("bad_request");
    }
    const ok = await call("POST", "/v1/sync", { body: { v: "1.0", games: [{ gameId: game.gameId, seatSecret: game.white }] } });
    expect(ok.status).toBe(200);
  });
});
