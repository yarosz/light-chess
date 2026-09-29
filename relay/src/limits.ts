// What the Worker checks before it routes a request: the scheme (plain HTTP is refused, docs/protocol.md
// "Conventions") and the key the rate limits count under (F11, L1, L2).

/** The key every request with a missing or unreadable `CF-Connecting-IP` shares. */
export const UNKNOWN_CLIENT = "unknown";

const IPV4 = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
const HEXTET = /^[0-9a-f]{1,4}$/;

/**
 * The rate-limit key for a client address (L1): an IPv4 address as it is, an IPv6 address as its
 * /64 (the first four hextets, expanded and lowercase, such as `2001:db8:0:0::/64`), because one
 * subscriber is usually handed a whole /64 and can pick a fresh address in it for every request.
 * An IPv4-mapped IPv6 address (`::ffff:1.2.3.4`) is its IPv4 address. Anything else, or no header,
 * is [UNKNOWN_CLIENT].
 */
export function clientKey(header: string | null): string {
  const raw = header?.trim().toLowerCase() ?? "";
  if (IPV4.test(raw)) return raw;
  const hextets = ipv6Hextets(raw);
  if (!hextets) return UNKNOWN_CLIENT;
  if (hextets.slice(0, 5).every((h) => h === 0) && hextets[5] === 0xffff) {
    const [hi, lo] = [hextets[6]!, hextets[7]!];
    return [hi >> 8, hi & 0xff, lo >> 8, lo & 0xff].join(".");
  }
  return `${hextets.slice(0, 4).map((h) => h.toString(16)).join(":")}::/64`;
}

/** The eight hextets of an IPv6 address, or null. Takes `::` and a dotted IPv4 tail; no zone id. */
function ipv6Hextets(address: string): number[] | null {
  if (!address.includes(":")) return null;
  // A dotted IPv4 tail ("::ffff:1.2.3.4") becomes its two hextets ("::ffff:102:304").
  const lastColon = address.lastIndexOf(":");
  const last = address.slice(lastColon + 1);
  let text = address;
  if (last.includes(".")) {
    if (!IPV4.test(last)) return null;
    const [a, b, c, d] = last.split(".").map(Number) as [number, number, number, number];
    text = `${address.slice(0, lastColon + 1)}${((a << 8) | b).toString(16)}:${((c << 8) | d).toString(16)}`;
  }
  const halves = text.split("::");
  if (halves.length > 2) return null;
  const parse = (part: string): number[] | null => {
    if (part === "") return [];
    const groups = part.split(":");
    if (!groups.every((g) => HEXTET.test(g))) return null;
    return groups.map((g) => parseInt(g, 16));
  };
  const head = parse(halves[0]!);
  const rest = halves.length === 2 ? parse(halves[1]!) : [];
  if (!head || !rest) return null;
  const given = head.length + rest.length;
  if (halves.length === 1) return given === 8 ? head : null;
  if (given > 7) return null;
  return [...head, ...Array<number>(8 - given).fill(0), ...rest];
}

/**
 * The hosts a plain-HTTP request may name: a local `wrangler dev` reached from this machine
 * (localhost, 127.0.0.1, [::1]) or from the Android emulator (10.0.2.2, its alias for the host), the
 * same set the Tool's debug build may talk plain HTTP to (`RelayConfig`). The deployed Worker is
 * routed only by its custom domain (W11), so none of these can reach it there.
 */
const LOCAL_HOSTS: ReadonlySet<string> = new Set(["localhost", "127.0.0.1", "[::1]", "10.0.2.2"]);

/** Whether the Worker refuses [url] for coming over plain HTTP (L3). */
export function isRefusedPlainHttp(url: URL): boolean {
  return url.protocol === "http:" && !LOCAL_HOSTS.has(url.hostname);
}
