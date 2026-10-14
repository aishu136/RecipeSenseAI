# recipetool frontend

Next.js (App Router) UI for the recipetool Quarkus API. See the main [README](../README.md#running-locally) for running the backend.

```shell script
npm install
npm run dev      # http://localhost:3000
npm run build && npm start   # production
```

| Page | Endpoint |
|---|---|
| `/` Recipe | `POST /recipe/generate`, or `POST /recipe/stream` with "Stream live progress" |
| `/meal-plan` | `POST /meal-plan` |
| `/autonomous` Agent | `POST /autonomous/stream` |
| `/profile` | `GET /me`, `/users/{userId}/restrictions`, `/preferences` and `/similar` (signed in) |

`src/app/api/[...path]/route.ts` forwards `/api/*` to `BACKEND_URL` (default `http://localhost:8080`), streaming server-sent events through as they arrive, with the signed-in user's access token (refreshed when it expires).

## Signing in

`src/app/auth/login`, `callback` and `logout` sign users in to the OIDC provider (`OIDC_ISSUER`; see `.env.example`) with the authorization code flow and PKCE, all on the server: the client secret and tokens never reach the browser. The access, refresh and ID tokens live in httpOnly cookies; the `/api` proxy refreshes the access token when it expires, and `logout` sends the ID token to the provider so it ends the session without asking to confirm. `src/lib/oidc.ts` has the shared settings and helpers, and `src/lib/useCurrentUser.ts` tells pages who is signed in. Guests can generate 4 free recipes (the recipe page shows how many are left), and the proxy gives each browser a `recipetool_guest` cookie the backend counts them by. When the backend answers 401 (free recipes used up, a guest asking for a meal plan, or an ended session), the page opens the sign-in popup (`src/components/SignInDialog.tsx`) rather than showing an error. Other errors show the message the backend sends in the response body (e.g. an ingredient that conflicts with a saved allergy). The profile page asks guests to sign in.

## Styling

Components are styled with Tailwind CSS utility classes. The colour palette lives in Sass: `src/styles/_palette.scss` has a map per colour scheme, and `src/app/theme.scss` turns it into CSS variables (dark values under `prefers-color-scheme: dark`). `src/app/globals.css` loads Tailwind and maps those variables to Tailwind colours with `@theme`, so `bg-accent`, `text-muted` and so on follow the scheme. Tailwind isn't compiled through Sass, so keep Tailwind directives in `.css` files.
