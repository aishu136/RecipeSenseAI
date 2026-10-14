import { defineConfig } from "@playwright/test";

// Stack tests: the whole app, already running, with a real backend, Keycloak
// and Redis (see "End-to-end tests" in README.md). The frontend must be at
// http://localhost:3000 (or E2E_STACK_URL), the redirect URI Keycloak allows.
export default defineConfig({
  testDir: "./e2e/stack",
  timeout: 120_000,
  // The tests share alice's saved restrictions, so they run one at a time
  workers: 1,
  reporter: "list",
  use: {
    baseURL: process.env.E2E_STACK_URL ?? "http://localhost:3000",
    channel: process.env.E2E_BROWSER_CHANNEL || undefined,
    trace: "retain-on-failure",
  },
});
