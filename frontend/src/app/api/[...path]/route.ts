// Forwards /api/* to the Quarkus backend, so the browser never calls it
// directly (no CORS setup needed) and server-sent events stream through.
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

async function forward(request: Request, ctx: RouteContext<"/api/[...path]">) {
  const { path } = await ctx.params;
  const url = new URL(request.url);
  const target = `${BACKEND_URL}/${path.map(encodeURIComponent).join("/")}${url.search}`;

  const headers = new Headers();
  for (const name of ["content-type", "accept"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }

  let response: Response;
  try {
    response = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" ? undefined : await request.arrayBuffer(),
      signal: request.signal,
      cache: "no-store",
    });
  } catch {
    return new Response(`Backend unreachable at ${BACKEND_URL}`, { status: 502 });
  }

  const responseHeaders = new Headers();
  const contentType = response.headers.get("content-type");
  if (contentType) responseHeaders.set("content-type", contentType);
  // no-transform stops response compression from buffering event streams
  responseHeaders.set("cache-control", "no-cache, no-transform");

  return new Response(response.body, { status: response.status, headers: responseHeaders });
}

export { forward as GET, forward as POST };
