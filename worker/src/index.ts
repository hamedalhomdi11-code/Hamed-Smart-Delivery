import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";

interface Env {
  DB: D1Database;
  AUTH0_DOMAIN: string;
  AUTH0_AUDIENCE: string;
  AUTH0_MGMT_CLIENT_ID?: string;
  AUTH0_MGMT_CLIENT_SECRET?: string;
  AUTH0_DB_CONNECTION?: string;
  AUTH0_MGMT_SCOPE?: string;
  ADMIN_USERNAME?: string;
  ADMIN_INITIAL_PASSWORD?: string;
  ADMIN_SESSION_DAYS?: string;
}

type AuthenticatedIdentity = { subject: string; claims: JWTPayload };
type AdminIdentity = { username: string; tokenHash: string };
const jwksCache = new Map<string, ReturnType<typeof createRemoteJWKSet>>();
let managementToken: { value: string; expiresAt: number } | null = null;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function randomBytes(size: number): Uint8Array {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  return bytes;
}
async function sha256(value: string): Promise<string> {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))));
}
async function derivePassword(password: string, salt: Uint8Array, iterations = 210000): Promise<string> {
  const material = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, material, 256);
  return bytesToHex(new Uint8Array(bits));
}
function constantTimeEqual(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  let diff = left.length ^ right.length;
  const max = Math.max(left.length, right.length);
  for (let i = 0; i < max; i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  return diff === 0;
}
async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const value: unknown = await request.json();
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch { return null; }
}
function getJwks(domain: string) {
  const normalized = domain.replace(/^https?:\/\//, "").replace(/\/$/, "");
  let jwks = jwksCache.get(normalized);
  if (!jwks) {
    jwks = createRemoteJWKSet(new URL(`https://${normalized}/.well-known/jwks.json`));
    jwksCache.set(normalized, jwks);
  }
  return { normalized, jwks };
}
async function authenticate(request: Request, env: Env): Promise<AuthenticatedIdentity | Response> {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "");
  if (!match) return json({ error: "missing_bearer_token" }, 401);
  try {
    const { normalized, jwks } = getJwks(env.AUTH0_DOMAIN);
    const verified = await jwtVerify(match[1], jwks, {
      issuer: `https://${normalized}/`, audience: env.AUTH0_AUDIENCE, algorithms: ["RS256"],
    });
    if (!verified.payload.sub) return json({ error: "token_subject_missing" }, 401);
    return { subject: verified.payload.sub, claims: verified.payload };
  } catch { return json({ error: "invalid_access_token" }, 401); }
}
async function driverSession(request: Request, env: Env): Promise<Response> {
  const identity = await authenticate(request, env);
  if (identity instanceof Response) return identity;
  const now = Date.now();
  try {
    const account = await env.DB.prepare(
      `SELECT id, username, status, valid_from_ms, valid_until_ms
       FROM driver_accounts WHERE auth0_sub = ? LIMIT 1`,
    ).bind(identity.subject).first<{
      id: string; username: string; status: string; valid_from_ms: number; valid_until_ms: number;
    }>();
    if (!account) return json({ error: "driver_account_not_provisioned", serverTimeMs: now }, 403);
    if (account.status !== "active") return json({ error: "driver_account_disabled", serverTimeMs: now }, 403);
    if (now < account.valid_from_ms) return json({ error: "driver_account_not_started", validFromMs: account.valid_from_ms, serverTimeMs: now }, 403);
    if (now >= account.valid_until_ms) return json({ error: "driver_account_expired", validUntilMs: account.valid_until_ms, serverTimeMs: now }, 403);
    await env.DB.prepare("UPDATE driver_accounts SET last_auth_check_ms = ? WHERE id = ?").bind(now, account.id).run();
    return json({
      authenticated: true, account: { id: account.id, username: account.username, status: account.status },
      serverTimeMs: now, validFromMs: account.valid_from_ms, validUntilMs: account.valid_until_ms,
      offlineAuthorization: null,
      offlineAuthorizationStatus: "not_implemented",
    });
  } catch { return json({ error: "account_database_unavailable" }, 503); }
}
async function bootstrapAdmin(env: Env, username: string, password: string): Promise<boolean> {
  const existing = await env.DB.prepare("SELECT username, salt, password_hash, iterations FROM admin_credentials WHERE id = 1")
    .first<{ username: string; salt: string; password_hash: string; iterations: number }>();
  if (existing) {
    if (existing.username.toLowerCase() !== username.toLowerCase()) return false;
    const hash = await derivePassword(password, Uint8Array.from(existing.salt.match(/.{2}/g)?.map(x => parseInt(x, 16)) ?? []), existing.iterations);
    return constantTimeEqual(hash, existing.password_hash);
  }
  if (!env.ADMIN_USERNAME || !env.ADMIN_INITIAL_PASSWORD ||
      !constantTimeEqual(username.toLowerCase(), env.ADMIN_USERNAME.toLowerCase()) ||
      !constantTimeEqual(await sha256(password), await sha256(env.ADMIN_INITIAL_PASSWORD))) return false;
  const salt = randomBytes(16);
  const iterations = 210000;
  const hash = await derivePassword(password, salt, iterations);
  await env.DB.prepare(
    "INSERT INTO admin_credentials (id, username, salt, password_hash, iterations, created_at_ms, updated_at_ms) VALUES (1, ?, ?, ?, ?, ?, ?)",
  ).bind(env.ADMIN_USERNAME, bytesToHex(salt), hash, iterations, Date.now(), Date.now()).run();
  return true;
}
async function adminLogin(request: Request, env: Env): Promise<Response> {
  if (!env.ADMIN_USERNAME) return json({ error: "admin_username_not_configured" }, 503);
  const body = await readJson(request);
  const username = typeof body?.username === "string" ? body.username.trim().toLowerCase() : "";
  const password = typeof body?.password === "string" ? body.password : "";
  if (!username || !password || username.length > 80 || password.length > 256) return json({ error: "invalid_credentials" }, 401);
  const bootstrapRow = await env.DB.prepare("SELECT id FROM admin_credentials WHERE id = 1").first<{ id: number }>();
  if (!bootstrapRow && !env.ADMIN_INITIAL_PASSWORD) return json({ error: "admin_bootstrap_secret_not_configured" }, 503);
  const ipHash = await sha256(request.headers.get("CF-Connecting-IP") ?? "unknown-client");
  const now = Date.now();
  const attempts = await env.DB.prepare("SELECT failures, window_started_ms, blocked_until_ms FROM admin_login_attempts WHERE ip_hash = ?")
    .bind(ipHash).first<{ failures: number; window_started_ms: number; blocked_until_ms: number }>();
  if (attempts?.blocked_until_ms && attempts.blocked_until_ms > now) return json({ error: "admin_login_rate_limited" }, 429);
  try {
    const ok = await bootstrapAdmin(env, username, password);
    if (!ok) {
      const failures = attempts && now - attempts.window_started_ms < 15 * 60 * 1000 ? attempts.failures + 1 : 1;
      const windowStarted = failures === 1 ? now : attempts!.window_started_ms;
      const blockedUntil = failures >= 5 ? now + 15 * 60 * 1000 : 0;
      await env.DB.prepare("INSERT INTO admin_login_attempts (ip_hash, failures, window_started_ms, blocked_until_ms) VALUES (?, ?, ?, ?) ON CONFLICT(ip_hash) DO UPDATE SET failures = excluded.failures, window_started_ms = excluded.window_started_ms, blocked_until_ms = excluded.blocked_until_ms")
        .bind(ipHash, failures, windowStarted, blockedUntil).run();
      return json({ error: failures >= 5 ? "admin_login_rate_limited" : "invalid_credentials" }, failures >= 5 ? 429 : 401);
    }
    await env.DB.prepare("DELETE FROM admin_login_attempts WHERE ip_hash = ?").bind(ipHash).run();
    const token = base64Url(randomBytes(32));
    const tokenHash = await sha256(token);
    const days = Math.min(90, Math.max(1, Number(env.ADMIN_SESSION_DAYS ?? "30") || 30));
    const expires = Date.now() + days * 86400000;
    await env.DB.prepare("INSERT INTO admin_sessions (token_hash, username, expires_at_ms, created_at_ms, last_seen_at_ms) VALUES (?, ?, ?, ?, ?)")
      .bind(tokenHash, env.ADMIN_USERNAME, expires, Date.now(), Date.now()).run();
    await env.DB.prepare("INSERT INTO audit_log (id, action, target_type, metadata_json, created_at_ms) VALUES (?, 'admin_login', 'admin', '{}', ?)")
      .bind(crypto.randomUUID(), Date.now()).run();
    return json({ authenticated: true, token, username: env.ADMIN_USERNAME, expiresAtMs: expires });
  } catch { return json({ error: "admin_authentication_unavailable" }, 503); }
}
async function requireAdmin(request: Request, env: Env): Promise<AdminIdentity | Response> {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "");
  if (!match) return json({ error: "admin_session_required" }, 401);
  const tokenHash = await sha256(match[1]);
  const session = await env.DB.prepare(
    "SELECT username, expires_at_ms FROM admin_sessions WHERE token_hash = ? LIMIT 1",
  ).bind(tokenHash).first<{ username: string; expires_at_ms: number }>();
  if (!session || session.expires_at_ms <= Date.now()) return json({ error: "admin_session_expired" }, 401);
  const now = Date.now();
  await env.DB.prepare("UPDATE admin_sessions SET last_seen_at_ms = ?, expires_at_ms = ? WHERE token_hash = ?")
    .bind(now, now + 90 * 86400000, tokenHash).run();
  return { username: session.username, tokenHash };
}
async function revokeAdmin(request: Request, env: Env): Promise<Response> {
  const admin = await requireAdmin(request, env);
  if (admin instanceof Response) return admin;
  await env.DB.prepare("DELETE FROM admin_sessions WHERE token_hash = ?").bind(admin.tokenHash).run();
  return json({ authenticated: false });
}
async function changeAdminPassword(request: Request, env: Env): Promise<Response> {
  const admin = await requireAdmin(request, env);
  if (admin instanceof Response) return admin;
  const body = await readJson(request);
  const current = typeof body?.currentPassword === "string" ? body.currentPassword : "";
  const next = typeof body?.newPassword === "string" ? body.newPassword : "";
  if (next.length < 10 || next.length > 256) return json({ error: "new_password_must_be_at_least_10_characters" }, 400);
  const row = await env.DB.prepare("SELECT salt, password_hash, iterations FROM admin_credentials WHERE id = 1")
    .first<{ salt: string; password_hash: string; iterations: number }>();
  if (!row) return json({ error: "admin_not_initialized" }, 409);
  const saltBytes = Uint8Array.from(row.salt.match(/.{2}/g)?.map(x => parseInt(x, 16)) ?? []);
  if (!constantTimeEqual(await derivePassword(current, saltBytes, row.iterations), row.password_hash)) return json({ error: "current_password_invalid" }, 401);
  const salt = randomBytes(16);
  const hash = await derivePassword(next, salt);
  await env.DB.prepare("UPDATE admin_credentials SET salt = ?, password_hash = ?, iterations = 210000, updated_at_ms = ? WHERE id = 1")
    .bind(bytesToHex(salt), hash, Date.now()).run();
  await env.DB.prepare("DELETE FROM admin_sessions").run();
  return json({ passwordChanged: true, reLoginRequired: true });
}
async function getManagementToken(env: Env): Promise<string> {
  if (!env.AUTH0_MGMT_CLIENT_ID || !env.AUTH0_MGMT_CLIENT_SECRET || !env.AUTH0_DB_CONNECTION) {
    throw new Error("auth0_management_api_not_configured");
  }
  if (managementToken && managementToken.expiresAt > Date.now() + 60000) return managementToken.value;
  const domain = env.AUTH0_DOMAIN.replace(/^https?:\/\//, "").replace(/\/$/, "");
  const response = await fetch(`https://${domain}/oauth/token`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({
      grant_type: "client_credentials", client_id: env.AUTH0_MGMT_CLIENT_ID,
      client_secret: env.AUTH0_MGMT_CLIENT_SECRET, audience: `https://${domain}/api/v2/`,
    }),
  });
  if (!response.ok) throw new Error("auth0_management_token_failed");
  const data = await response.json() as { access_token: string; expires_in: number };
  managementToken = { value: data.access_token, expiresAt: Date.now() + data.expires_in * 1000 };
  return data.access_token;
}
async function auth0Request(env: Env, path: string, method = "GET", body?: unknown): Promise<any> {
  const token = await getManagementToken(env);
  const domain = env.AUTH0_DOMAIN.replace(/^https?:\/\//, "").replace(/\/$/, "");
  const response = await fetch(`https://${domain}/api/v2/${path}`, {
    method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const payload = await response.json().catch(() => ({})) as Record<string, unknown>;
  if (!response.ok) throw new Error(typeof payload.message === "string" ? payload.message : "auth0_management_request_failed");
  return payload;
}
function addDuration(startMs: number, value: number, unit: string): number {
  const date = new Date(startMs);
  if (unit === "days") return startMs + value * 86400000;
  if (unit === "weeks") return startMs + value * 7 * 86400000;
  if (unit !== "months") throw new Error("invalid_duration_unit");
  const day = date.getUTCDate();
  date.setUTCDate(1);
  date.setUTCMonth(date.getUTCMonth() + value);
  const lastDay = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate();
  date.setUTCDate(Math.min(day, lastDay));
  return date.getTime();
}
async function audit(env: Env, action: string, targetId: string | null, metadata: unknown = {}): Promise<void> {
  await env.DB.prepare("INSERT INTO audit_log (id, action, target_type, target_id, metadata_json, created_at_ms) VALUES (?, ?, 'driver', ?, ?, ?)")
    .bind(crypto.randomUUID(), action, targetId, JSON.stringify(metadata), Date.now()).run();
}
async function listDrivers(env: Env): Promise<Response> {
  const rows = await env.DB.prepare(
    "SELECT id, username, status, valid_from_ms, valid_until_ms, duration_value, duration_unit, created_at_ms, last_auth_check_ms FROM driver_accounts ORDER BY created_at_ms DESC",
  ).all();
  return json({ drivers: rows.results ?? [] });
}
async function createDriver(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const username = typeof body?.username === "string" ? body.username.trim() : "";
  const password = typeof body?.password === "string" ? body.password : "";
  const durationValue = Number(body?.durationValue);
  const durationUnit = typeof body?.durationUnit === "string" ? body.durationUnit : "";
  if (!/^[a-z0-9_.-]{1,15}$/.test(username)) return json({ error: "username_format_invalid" }, 400);
  if (password.length < 10 || password.length > 128) return json({ error: "driver_password_must_be_at_least_10_characters" }, 400);
  if (!Number.isInteger(durationValue) || durationValue < 1 || durationValue > 3650 || !["days", "weeks", "months"].includes(durationUnit)) {
    return json({ error: "duration_invalid" }, 400);
  }
  const now = Date.now();
  const validUntil = addDuration(now, durationValue, durationUnit);
  let auth0UserId: string | null = null;
  try {
    // Auth0 database connections configured for username sign-in require both
    // username and email. Use a reserved .invalid address: it is unique per
    // driver, cannot receive mail, and password resets remain administrator-led.
    const internalEmail = `${username}@drivers.hamed-smart-delivery.invalid`;
    const user = await auth0Request(env, "users", "POST", {
      connection: env.AUTH0_DB_CONNECTION, username, email: internalEmail,
      email_verified: true, verify_email: false, password,
      user_metadata: { product: "hamed-smart-delivery" },
      app_metadata: { role: "driver" },
    }) as { user_id?: string };
    auth0UserId = user.user_id ?? null;
    if (!auth0UserId) throw new Error("auth0_user_id_missing");
    const id = crypto.randomUUID();
    await env.DB.prepare(
      "INSERT INTO driver_accounts (id, auth0_sub, username, status, valid_from_ms, valid_until_ms, duration_value, duration_unit, created_at_ms, updated_at_ms) VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?, ?)",
    ).bind(id, auth0UserId, username, now, validUntil, durationValue, durationUnit, now, now).run();
    await audit(env, "driver_created", id, { username, durationValue, durationUnit, validUntil });
    return json({ created: true, driver: { id, username, status: "active", validFromMs: now, validUntilMs: validUntil } }, 201);
  } catch (error) {
    if (auth0UserId) {
      try { await auth0Request(env, `users/${encodeURIComponent(auth0UserId)}`, "DELETE"); } catch { /* best-effort rollback */ }
    }
    const message = error instanceof Error ? error.message : "driver_create_failed";
    if (message.includes("auth0_management_api_not_configured")) return json({ error: message }, 503);
    if (message.toLowerCase().includes("already exists") || message.includes("username")) return json({ error: "username_already_exists_or_auth0_rejected_it" }, 409);
    return json({ error: "driver_create_failed", detail: message.slice(0, 180) }, 502);
  }
}
async function updateDriver(request: Request, env: Env, id: string): Promise<Response> {
  const body = await readJson(request);
  const row = await env.DB.prepare("SELECT id, auth0_sub, username, valid_from_ms FROM driver_accounts WHERE id = ?").bind(id)
    .first<{ id: string; auth0_sub: string; username: string; valid_from_ms: number }>();
  if (!row) return json({ error: "driver_not_found" }, 404);
  const value = Number(body?.durationValue);
  const unit = typeof body?.durationUnit === "string" ? body.durationUnit : "";
  if (!Number.isInteger(value) || value < 1 || value > 3650 || !["days", "weeks", "months"].includes(unit)) return json({ error: "duration_invalid" }, 400);
  const validUntil = addDuration(Math.max(Date.now(), row.valid_from_ms), value, unit);
  await env.DB.prepare("UPDATE driver_accounts SET valid_from_ms = ?, valid_until_ms = ?, duration_value = ?, duration_unit = ?, updated_at_ms = ? WHERE id = ?")
    .bind(Date.now(), validUntil, value, unit, Date.now(), id).run();
  await audit(env, "driver_validity_updated", id, { durationValue: value, durationUnit: unit, validUntil });
  return json({ updated: true, validUntilMs: validUntil });
}
async function setDriverStatus(env: Env, id: string, enabled: boolean): Promise<Response> {
  const row = await env.DB.prepare("SELECT id, auth0_sub FROM driver_accounts WHERE id = ?").bind(id)
    .first<{ id: string; auth0_sub: string }>();
  if (!row) return json({ error: "driver_not_found" }, 404);
  try {
    await auth0Request(env, `users/${encodeURIComponent(row.auth0_sub)}`, "PATCH", { blocked: !enabled });
    const now = Date.now();
    await env.DB.prepare("UPDATE driver_accounts SET status = ?, disabled_at_ms = ?, updated_at_ms = ? WHERE id = ?")
      .bind(enabled ? "active" : "disabled", enabled ? null : now, now, id).run();
    await audit(env, enabled ? "driver_enabled" : "driver_disabled", id);
    return json({ updated: true, status: enabled ? "active" : "disabled" });
  } catch { return json({ error: "auth0_driver_status_update_failed" }, 502); }
}
async function resetDriverPassword(request: Request, env: Env, id: string): Promise<Response> {
  const body = await readJson(request);
  const password = typeof body?.password === "string" ? body.password : "";
  if (password.length < 10 || password.length > 128) return json({ error: "driver_password_must_be_at_least_10_characters" }, 400);
  const row = await env.DB.prepare("SELECT auth0_sub FROM driver_accounts WHERE id = ?").bind(id).first<{ auth0_sub: string }>();
  if (!row) return json({ error: "driver_not_found" }, 404);
  try {
    await auth0Request(env, `users/${encodeURIComponent(row.auth0_sub)}`, "PATCH", { password, connection: env.AUTH0_DB_CONNECTION });
    await audit(env, "driver_password_reset", id);
    return json({ passwordReset: true });
  } catch { return json({ error: "auth0_password_reset_failed" }, 502); }
}
async function adminRouter(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "POST" && url.pathname === "/v1/admin/login") return adminLogin(request, env);
  if (request.method === "GET" && url.pathname === "/v1/admin/setup") {
    return json({
      ready: Boolean(env.ADMIN_USERNAME && env.ADMIN_INITIAL_PASSWORD && env.AUTH0_MGMT_CLIENT_ID && env.AUTH0_MGMT_CLIENT_SECRET && env.AUTH0_DB_CONNECTION),
      adminBootstrapConfigured: Boolean(env.ADMIN_USERNAME && env.ADMIN_INITIAL_PASSWORD),
      auth0ManagementConfigured: Boolean(env.AUTH0_MGMT_CLIENT_ID && env.AUTH0_MGMT_CLIENT_SECRET && env.AUTH0_DB_CONNECTION),
      pairingCodeRequired: false,
    });
  }
  if (request.method === "POST" && url.pathname === "/v1/admin/logout") return revokeAdmin(request, env);
  if (request.method === "POST" && url.pathname === "/v1/admin/password") return changeAdminPassword(request, env);
  const admin = await requireAdmin(request, env);
  if (admin instanceof Response) return admin;
  if (request.method === "GET" && url.pathname === "/v1/admin/drivers") return listDrivers(env);
  if (request.method === "POST" && url.pathname === "/v1/admin/drivers") return createDriver(request, env);
  const match = /^\/v1\/admin\/drivers\/([0-9a-f-]+)(?:\/(enable|disable|password))?$/i.exec(url.pathname);
  if (!match) return json({ error: "not_found" }, 404);
  const [, id, action] = match;
  if (request.method === "PATCH" && !action) return updateDriver(request, env, id);
  if (request.method === "POST" && action === "enable") return setDriverStatus(env, id, true);
  if (request.method === "POST" && action === "disable") return setDriverStatus(env, id, false);
  if (request.method === "POST" && action === "password") return resetDriverPassword(request, env, id);
  return json({ error: "method_not_allowed" }, 405);
}
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204 });
    if (request.method === "GET" && url.pathname === "/health") {
      try {
        await env.DB.prepare("SELECT 1 AS ok").first();
        return json({ ok: true, service: "hamed-smart-delivery-api", database: "connected", timestampMs: Date.now() });
      } catch { return json({ ok: false, service: "hamed-smart-delivery-api", database: "unavailable" }, 503); }
    }
    if (request.method === "GET" && url.pathname === "/v1/driver/session") return driverSession(request, env);
    if (url.pathname.startsWith("/v1/admin/")) {
      try { return await adminRouter(request, env); }
      catch { return json({ error: "admin_service_unavailable" }, 503); }
    }
    return json({ error: "not_found" }, 404);
  },
};
