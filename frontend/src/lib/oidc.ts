// Server-side OIDC sign-in (authorization code flow with PKCE). Only route
// handlers import this: the client secret and tokens never reach the browser.
import { createHash, randomBytes } from "node:crypto";
import type { NextRequest, NextResponse } from "next/server";

// The access token the /api proxy sends to the backend
export const TOKEN_COOKIE = "recipetool_access_token";
// Gets a new access token when that one expires
export const REFRESH_COOKIE = "recipetool_refresh_token";
// Identifies the session at sign-out, so the provider ends it without asking to confirm
export const ID_TOKEN_COOKIE = "recipetool_id_token";
// state, PKCE verifier and where to return, kept during sign-in
export const SIGN_IN_COOKIE = "recipetool_sign_in";

export type SignIn = { state: string; verifier: string; returnTo: string };

type Discovery = {
  authorization_endpoint: string;
  token_endpoint: string;
  end_session_endpoint?: string;
};

// e.g. http://localhost:8180/realms/quarkus (Keycloak) or
// https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf (Cognito)
export const config = {
  issuer: process.env.OIDC_ISSUER || "http://localhost:8180/realms/quarkus",
  clientId: process.env.OIDC_CLIENT_ID || "recipetool",
  clientSecret: process.env.OIDC_CLIENT_SECRET || undefined,
  scope: process.env.OIDC_SCOPE || "openid",
};

let discovery: Promise<Discovery> | null = null;

export function discover(): Promise<Discovery> {
  discovery ??= fetch(`${config.issuer.replace(/\/$/, "")}/.well-known/openid-configuration`, { cache: "no-store" })
    .then((response) => {
      if (!response.ok) throw new Error(`OIDC discovery failed: ${response.status}`);
      return response.json() as Promise<Discovery>;
    })
    .catch((err) => {
      discovery = null; // retry on the next sign-in
      throw err;
    });
  return discovery;
}

// This app's own URL; set APP_URL when it runs behind a proxy that changes it
export function appUrl(request: NextRequest): string {
  return (process.env.APP_URL || request.nextUrl.origin).replace(/\/$/, "");
}

export function randomToken(): string {
  return randomBytes(32).toString("base64url");
}

export function codeChallenge(verifier: string): string {
  return createHash("sha256").update(verifier).digest("base64url");
}

// Only paths on this site, so sign-in can't redirect elsewhere
export function safeReturnTo(value: string | null): string {
  return value && value.startsWith("/") && !value.startsWith("//") ? value : "/profile";
}

// A token endpoint response
export type Tokens = {
  access_token: string;
  expires_in?: number;
  refresh_token?: string;
  // Keycloak's lifetime for the refresh token; other providers don't say
  refresh_expires_in?: number;
  // Sent with the "openid" scope
  id_token?: string;
};

// Refresh tokens without a stated lifetime are kept this long (the provider
// still decides when they stop working)
const DEFAULT_REFRESH_SECONDS = 30 * 24 * 60 * 60;
// Access tokens are dropped this long before they expire, so the proxy
// refreshes them before the backend would reject them
const EXPIRY_MARGIN_SECONDS = 30;

/**
 * Asks the token endpoint for tokens, e.g. with an authorization code or a
 * refresh token; throws if it doesn't issue an access token.
 */
export async function requestTokens(params: Record<string, string>): Promise<Tokens> {
  const body = new URLSearchParams({ ...params, client_id: config.clientId });
  if (config.clientSecret) body.set("client_secret", config.clientSecret);
  const response = await fetch((await discover()).token_endpoint, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
    cache: "no-store",
  });
  const tokens = (await response.json().catch(() => ({}))) as Partial<Tokens>;
  if (!response.ok || !tokens.access_token) throw new Error(`token request failed: ${response.status}`);
  return tokens as Tokens;
}

// Refreshes in progress, so parallel requests with the same expired session
// share one refresh (providers that rotate refresh tokens reject reuse)
const refreshing = new Map<string, Promise<Tokens>>();

export function refreshTokens(refreshToken: string): Promise<Tokens> {
  let pending = refreshing.get(refreshToken);
  if (!pending) {
    pending = requestTokens({ grant_type: "refresh_token", refresh_token: refreshToken }).finally(() =>
      setTimeout(() => refreshing.delete(refreshToken), 10_000),
    );
    refreshing.set(refreshToken, pending);
  }
  return pending;
}

// Keeps the tokens in httpOnly cookies on the response
export function setTokenCookies(response: NextResponse, request: NextRequest, tokens: Tokens) {
  const accessSeconds = Math.max((tokens.expires_in ?? 300) - EXPIRY_MARGIN_SECONDS, 10);
  response.cookies.set(TOKEN_COOKIE, tokens.access_token, cookieOptions(request, accessSeconds));
  // Some providers (e.g. Cognito) don't send a new refresh token on refresh: keep the old one
  const sessionSeconds = tokens.refresh_expires_in || DEFAULT_REFRESH_SECONDS;
  if (tokens.refresh_token) {
    response.cookies.set(REFRESH_COOKIE, tokens.refresh_token, cookieOptions(request, sessionSeconds));
  }
  // Kept as long as the session: providers accept an expired one as a sign-out hint
  if (tokens.id_token) {
    response.cookies.set(ID_TOKEN_COOKIE, tokens.id_token, cookieOptions(request, sessionSeconds));
  }
}

export function clearTokenCookies(response: NextResponse) {
  response.cookies.delete(TOKEN_COOKIE);
  response.cookies.delete(REFRESH_COOKIE);
  response.cookies.delete(ID_TOKEN_COOKIE);
}

export function cookieOptions(request: NextRequest, maxAge: number) {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: request.nextUrl.protocol === "https:",
    path: "/",
    maxAge,
  };
}
