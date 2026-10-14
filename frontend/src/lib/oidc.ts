// Server-side OIDC sign-in (authorization code flow with PKCE). Only route
// handlers import this: the client secret and tokens never reach the browser.
import { createHash, randomBytes } from "node:crypto";
import type { NextRequest } from "next/server";

// The access token the /api proxy sends to the backend
export const TOKEN_COOKIE = "recipetool_access_token";
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

export function cookieOptions(request: NextRequest, maxAge: number) {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: request.nextUrl.protocol === "https:",
    path: "/",
    maxAge,
  };
}
