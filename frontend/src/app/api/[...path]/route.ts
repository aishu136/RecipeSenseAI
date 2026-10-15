// Forwards /api/* to the Quarkus backend, so the browser never calls it
// directly (no CORS setup needed) and server-sent events stream through.
// Requests carry the signed-in user's access token, refreshed when it expires,
// and a guest id, which the backend counts guests' free recipes by.
import { NextResponse, type NextRequest } from "next/server";
import {
  REFRESH_COOKIE,
  TOKEN_COOKIE,
  clearTokenCookies,
  cookieOptions,
  refreshTokens,
  setTokenCookies,
  type Tokens,
} from "@/lib/oidc";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

// A random id per browser, so guests' free recipes are counted (a year, like any visitor cookie)
const GUEST_COOKIE = "recipetool_guest";
const GUEST_COOKIE_SECONDS = 365 * 24 * 60 * 60;

async function forward(request: NextRequest, ctx: RouteContext<"/api/[...path]">) {
  const { path } = await ctx.params;
  const url = new URL(request.url);
  const target = `${BACKEND_URL}/${path.map(encodeURIComponent).join("/")}${url.search}`;
  // Read once, so the request can be sent again after a refresh
  const body = request.method === "GET" ? undefined : await request.arrayBuffer();

  const savedGuestId = request.cookies.get(GUEST_COOKIE)?.value;
  const guestId = savedGuestId ?? crypto.randomUUID();

  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
  let token = request.cookies.get(TOKEN_COOKIE)?.value;
  // New tokens to hand back to the browser, or null when the session has ended
  let refreshed: Tokens | null | undefined;

  async function refresh(): Promise<string | undefined> {
    try {
      refreshed = await refreshTokens(refreshToken!);
      return refreshed.access_token;
    } catch {
      refreshed = null;
      return undefined;
    }
  }

  // The access token cookie expires a little before the token does
  if (!token && refreshToken) token = await refresh();

  let response: Response;
  try {
    response = await send(request, target, body, token, guestId);
    // Rejected anyway (e.g. revoked, or clocks disagree): refresh once and retry
    if (response.status === 401 && token && refreshToken && refreshed === undefined) {
      token = await refresh();
      if (token) response = await send(request, target, body, token, guestId);
    }
  } catch {
    // Still hand back any refreshed tokens: the old refresh token may no longer work
    return withCookies(new NextResponse(`Backend unreachable at ${BACKEND_URL}`, { status: 502 }));
  }

  const responseHeaders = new Headers();
  const contentType = response.headers.get("content-type");
  if (contentType) responseHeaders.set("content-type", contentType);
  // no-transform stops response compression from buffering event streams
  responseHeaders.set("cache-control", "no-cache, no-transform");

  return withCookies(new NextResponse(response.body, { status: response.status, headers: responseHeaders }));

  function withCookies(result: NextResponse) {
    if (refreshed) setTokenCookies(result, request, refreshed);
    else if (refreshed === null) clearTokenCookies(result);
    if (!savedGuestId) result.cookies.set(GUEST_COOKIE, guestId, cookieOptions(request, GUEST_COOKIE_SECONDS));
    return result;
  }
}

function send(
  request: NextRequest,
  target: string,
  body: ArrayBuffer | undefined,
  token: string | undefined,
  guestId: string,
) {
  const headers = new Headers();
  for (const name of ["content-type", "accept", "idempotency-key"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  if (token) headers.set("authorization", `Bearer ${token}`);
  // The backend ignores it for signed-in users
  headers.set("x-guest-id", guestId);
  return fetch(target, {
    method: request.method,
    headers,
    body,
    signal: request.signal,
    cache: "no-store",
  });
}

export { forward as GET, forward as POST, forward as PUT, forward as DELETE };
