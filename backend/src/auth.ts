// Auth primitives — all via Web Crypto, no external deps.
//   * Passwords: PBKDF2-HMAC-SHA256 (random salt, 100k iterations), stored as
//     `pbkdf2$iterations$salt$hash`. Verified with a timing-safe comparison.
//   * Session tokens: 32 random bytes (hex). Only the SHA-256 of the token is stored
//     (`sessions.token_hash`); the raw token lives only on the client.
import type { MiddlewareHandler } from "hono";

const PBKDF2_ITERATIONS = 100_000;
const enc = new TextEncoder();

export type AuthUser = { id: string; email: string; name: string; role: string };
export type AuthEnv = { Bindings: Env; Variables: { user: AuthUser } };

function b64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}
function unb64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations, hash: "SHA-256" }, key, 256);
  return new Uint8Array(bits);
}

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(password, salt, PBKDF2_ITERATIONS);
  return `pbkdf2$${PBKDF2_ITERATIONS}$${b64(salt)}$${b64(hash)}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, iterStr, saltB64, hashB64] = stored.split("$");
  if (scheme !== "pbkdf2") return false;
  const expected = unb64(hashB64);
  const actual = await pbkdf2(password, unb64(saltB64), Number(iterStr));
  if (actual.length !== expected.length) return false;
  return crypto.subtle.timingSafeEqual(actual, expected);
}

/** A fresh session token (hex). Give the raw token to the client; store only its hash. */
export function generateToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256Hex(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Create a 30-day session for a user and return the raw token. */
export async function createSession(db: D1Database, userId: string): Promise<string> {
  const token = generateToken();
  await db
    .prepare("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, datetime('now', '+30 days'))")
    .bind(await sha256Hex(token), userId)
    .run();
  return token;
}

function bearer(c: Parameters<MiddlewareHandler<AuthEnv>>[0]): string | null {
  const h = c.req.header("Authorization") ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : null;
}

/** Gate a route on a valid, unexpired session; attaches the user to the context. */
export const requireAuth: MiddlewareHandler<AuthEnv> = async (c, next) => {
  const token = bearer(c);
  if (!token) return c.json({ error: "unauthorized" }, 401);
  const user = await c.env.DB.prepare(
    `SELECT u.id, u.email, u.name, u.role FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = ? AND s.expires_at > datetime('now')`
  )
    .bind(await sha256Hex(token))
    .first<AuthUser>();
  if (!user) return c.json({ error: "unauthorized" }, 401);
  c.set("user", user);
  await next();
};

export { bearer };
