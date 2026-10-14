// Forwards /api/* to the Quarkus backend, so the browser never calls it
// directly (no CORS setup needed) and server-sent events stream through.
// Requests carry the signed-in user's access token, refreshed when it expires.
import { NextResponse, type NextRequest } from "next/server";
import { REFRESH_COOKIE, TOKEN_COOKIE, clearTokenCookies, refreshTokens, setTokenCookies, type Tokens } from "@/lib/oidc";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

async function forward(request: NextRequest, ctx: RouteContext<"/api/[...path]">) {
  const { path } = await ctx.params;
  const url = new URL(request.url);
  const target = `${BACKEND_URL}/${path.map(encodeURIComponent).join("/")}${url.search}`;
  // Read once, so the request can be sent again after a refresh
  const body = request.method === "GET" ? undefined : await request.arrayBuffer();

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
    response = await send(request, target, body, token);
    // Rejected anyway (e.g. revoked, or clocks disagree): refresh once and retry
    if (response.status === 401 && token && refreshToken && refreshed === undefined) {
      token = await refresh();
      if (token) response = await send(request, target, body, token);
    }
  } catch {
    return new Response(`Backend unreachable at ${BACKEND_URL}`, { status: 502 });
  }

  const responseHeaders = new Headers();
  const contentType = response.headers.get("content-type");
  if (contentType) responseHeaders.set("content-type", contentType);
  // no-transform stops response compression from buffering event streams
  responseHeaders.set("cache-control", "no-cache, no-transform");

  const result = new NextResponse(response.body, { status: response.status, headers: responseHeaders });
  if (refreshed) setTokenCookies(result, request, refreshed);
  else if (refreshed === null) clearTokenCookies(result);
  return result;
}

function send(request: NextRequest, target: string, body: ArrayBuffer | undefined, token: string | undefined) {
  const headers = new Headers();
  for (const name of ["content-type", "accept"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  if (token) headers.set("authorization", `Bearer ${token}`);
  return fetch(target, {
    method: request.method,
    headers,
    body,
    signal: request.signal,
    cache: "no-store",
  });
}

export { forward as GET, forward as POST, forward as PUT, forward as DELETE };
