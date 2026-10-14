import type { Page, Route } from "@playwright/test";

// Fakes the backend behind /api/* in the browser, so UI tests need no backend.
// Each handler gets the request's route and answers it; unset ones fail the request.
export type Handlers = Partial<Record<string, (route: Route) => Promise<void> | void>>;

export const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

export const text = (route: Route, body: string, status: number) =>
  route.fulfill({ status, contentType: "text/plain", body });

export const SIGNED_OUT: Handlers = { "GET /me": (route) => text(route, "", 401) };

export const signedInAs = (userId: string): Handlers => ({ "GET /me": (route) => json(route, { userId }) });

/**
 * Answers /api/<path> requests with the handler for "<METHOD> /<path>", and
 * returns the paths requested, so tests can check what was (not) called.
 */
export async function fakeApi(page: Page, handlers: Handlers): Promise<string[]> {
  const calls: string[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api/, "");
    const key = `${request.method()} ${path}`;
    calls.push(key);
    const handler = handlers[key];
    if (handler) await handler(route);
    else await text(route, `No fake for ${key}`, 500);
  });
  return calls;
}
