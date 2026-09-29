// Seat secrets, join tokens and Invite Codes. The Relay keeps only SHA-256 digests of them.

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** 32 random bytes as unpadded base64url (43 characters): a seat secret or a join token. */
export function newToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** A new Invite Code in canonical form: 8 Crockford base32 characters, 40 random bits. */
export function newInviteCode(): string {
  // 256 is a multiple of 32, so masking a byte keeps every character equally likely.
  return [...crypto.getRandomValues(new Uint8Array(8))].map((b) => CROCKFORD[b & 31]).join("");
}

/** ABCDEFGH as ABCD-EFGH, the way a phone shows it. */
export const displayInviteCode = (code: string): string => `${code.slice(0, 4)}-${code.slice(4)}`;

/**
 * Reads a code the way Crockford base32 decodes: any case, "-" and spaces ignored, O as 0, I and L
 * as 1. Returns the canonical 8 characters, or null if it can't be a code.
 */
export function normalizeInviteCode(raw: string): string | null {
  const code = raw
    .toUpperCase()
    .replace(/[-\s]/g, "")
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1");
  if (code.length !== 8) return null;
  for (const c of code) if (!CROCKFORD.includes(c)) return null;
  return code;
}
