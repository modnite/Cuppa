import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import { store } from "./store.js";
import { log } from "./logger.js";

const COOKIE_NAME = "cuppa_session";
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

interface Session {
  expiresAt: number;
}

/** Optional single-admin-password auth. Off by default: an empty password means open. */
class Auth {
  private sessions = new Map<string, Session>();

  get required(): boolean {
    return Boolean(store.settings.adminPasswordHash && store.settings.adminPasswordSalt);
  }

  setPassword(password: string): void {
    if (!password) {
      store.updateSettings({ adminPasswordHash: null, adminPasswordSalt: null });
      this.sessions.clear();
      return;
    }
    const salt = randomBytes(16).toString("hex");
    const hash = scryptSync(password, salt, 64).toString("hex");
    store.updateSettings({ adminPasswordHash: hash, adminPasswordSalt: salt });
    this.sessions.clear();
  }

  verify(password: string): boolean {
    const { adminPasswordHash, adminPasswordSalt } = store.settings;
    if (!adminPasswordHash || !adminPasswordSalt) return true;
    const candidate = scryptSync(password, adminPasswordSalt, 64);
    const expected = Buffer.from(adminPasswordHash, "hex");
    return candidate.length === expected.length && timingSafeEqual(candidate, expected);
  }

  issue(): string {
    const token = randomBytes(32).toString("hex");
    this.sessions.set(token, { expiresAt: Date.now() + SESSION_TTL_MS });
    this.prune();
    return token;
  }

  isValid(token: string | undefined): boolean {
    if (!token) return false;
    const session = this.sessions.get(token);
    if (!session) return false;
    if (session.expiresAt < Date.now()) {
      this.sessions.delete(token);
      return false;
    }
    return true;
  }

  revoke(token: string | undefined): void {
    if (token) this.sessions.delete(token);
  }

  private prune(): void {
    const now = Date.now();
    for (const [token, session] of this.sessions) {
      if (session.expiresAt < now) this.sessions.delete(token);
    }
  }
}

export const auth = new Auth();

export function sessionToken(request: FastifyRequest): string | undefined {
  return request.cookies[COOKIE_NAME];
}

export function setSessionCookie(reply: FastifyReply, token: string): void {
  reply.setCookie(COOKIE_NAME, token, {
    path: "/",
    httpOnly: true,
    sameSite: "lax",
    maxAge: SESSION_TTL_MS / 1000,
  });
}

export function clearSessionCookie(reply: FastifyReply): void {
  reply.clearCookie(COOKIE_NAME, { path: "/" });
}

/**
 * Guard for API routes. Returns true when the request may proceed; otherwise
 * it sends 401 and the caller should return early.
 */
export function requireAuth(request: FastifyRequest, reply: FastifyReply): boolean {
  if (!auth.required) return true;
  if (auth.isValid(sessionToken(request))) return true;
  log.debug(`Rejecting unauthenticated ${request.method} ${request.url}`);
  reply.code(401).send({ error: "unauthorized" });
  return false;
}
