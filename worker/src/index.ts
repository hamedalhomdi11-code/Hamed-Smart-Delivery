import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";

interface Env {
  DB: D1Database;
  AUTH0_DOMAIN: string;
  AUTH0_AUDIENCE: string;
  AUTH0_MGMT_CLIENT_ID?: string;
  AUTH0_MGMT_CLIENT_SECRET?: string;
  AUTH0_DB_CONNECTION?: string;
  ADMIN_ENROLLMENT_CODE_HASH?: string;
}

type AuthenticatedIdentity = { subject: string; claims: JWTPayload };

const jwksCache = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
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
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match) return json({ error: "missing_bearer_token" }, 401);

  try {
    const { normalized, jwks } = getJwks(env.AUTH0_DOMAIN);
    const verified = await jwtVerify(match[1], jwks, {
      issuer: `https://${normalized}/`,
      audience: env.AUTH0_AUDIENCE,
      algorithms: ["RS256"],
    });
    if (!verified.payload.sub) return json({ error: "token_subject_missing" }, 401);
    return { subject: verified.payload.sub, claims: verified.payload };
  } catch {
    return json({ error: "invalid_access_token" }, 401);
  }
}

async function driverSession(request: Request, env: Env): Promise<Response> {
  const identity = await authenticate(request, env);
  if (identity instanceof Response) return identity;

  const now = Date.now();
  const account = await env.DB.prepare(
    `SELECT id, username, status, valid_from_ms, valid_until_ms
       FROM driver_accounts WHERE auth0_sub = ? LIMIT 1`,
  ).bind(identity.subject).first<{
    id: string; username: string; status: string;
    valid_from_ms: number; valid_until_ms: number;
  }>();

  if (!account) return json({ error: "driver_account_not_provisioned", serverTimeMs: now }, 403);
  if (account.status !== "active") return json({ error: "driver_account_disabled", serverTimeMs: now }, 403);
  if (now < account.valid_from_ms) {
    return json({ error: "driver_account_not_started", validFromMs: account.valid_from_ms, serverTimeMs: now }, 403);
  }
  if (now >= account.valid_until_ms) {
    return json({ error: "driver_account_expired", validUntilMs: account.valid_until_ms, serverTimeMs: now }, 403);
  }

  await env.DB.prepare("UPDATE driver_accounts SET last_auth_check_ms = ? WHERE id = ?")
    .bind(now, account.id).run();

  return json({
    authenticated: true,
    account: { id: account.id, username: account.username, status: account.status },
    serverTimeMs: now,
    validFromMs: account.valid_from_ms,
    validUntilMs: account.valid_until_ms,
    offlineAuthorization: null,
    offlineAuthorizationStatus: "not_implemented",
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "hamed-smart-delivery-api", timestampMs: Date.now() });
    }
    if (request.method === "GET" && url.pathname === "/v1/driver/session") {
      return driverSession(request, env);
    }
    if (request.method === "GET" && url.pathname === "/v1/admin/setup") {
      return json({
        ready: false,
        reason: "admin_device_enrollment_and_auth0_management_credentials_required",
        managementApiConfigured: Boolean(env.AUTH0_MGMT_CLIENT_ID && env.AUTH0_MGMT_CLIENT_SECRET && env.AUTH0_DB_CONNECTION),
        enrollmentConfigured: Boolean(env.ADMIN_ENROLLMENT_CODE_HASH),
      }, 503);
    }
    if (url.pathname.startsWith("/v1/admin/")) {
      return json({ error: "admin_device_enrollment_not_configured" }, 503);
    }
    return json({ error: "not_found" }, 404);
  },
};
