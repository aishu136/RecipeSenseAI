import { defineConfig } from "@playwright/test";

// UI tests: the pages against a faked backend (see e2e/ui/fakeApi.ts), so they
// need nothing else running. `npm run test:e2e` starts a dev server for them.
// Set E2E_BROWSER_CHANNEL (e.g. "msedge" or "chrome") to use an installed
// browser instead of running `npx playwright install chromium` first.
const PORT = 3100;

export default defineConfig({
  testDir: "./e2e/ui",
  timeout: 30_000,
  // The dev server compiles each page on its first visit, which can take seconds
  expect: { timeout: 15_000 },
  fullyParallel: true,
  reporter: "list",
  use: {
    baseURL: `http://localhost:${PORT}`,
    channel: process.env.E2E_BROWSER_CHANNEL || undefined,
    trace: "retain-on-failure",
  },
  webServer: {
    command: `npm run dev -- --port ${PORT}`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
