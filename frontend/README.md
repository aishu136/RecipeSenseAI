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

`src/app/api/[...path]/route.ts` forwards `/api/*` to `BACKEND_URL` (default `http://localhost:8080`), streaming server-sent events through as they arrive.

## Styling

Components are styled with Tailwind CSS utility classes. The colour palette lives in Sass: `src/styles/_palette.scss` has a map per colour scheme, and `src/app/theme.scss` turns it into CSS variables (dark values under `prefers-color-scheme: dark`). `src/app/globals.css` loads Tailwind and maps those variables to Tailwind colours with `@theme`, so `bg-accent`, `text-muted` and so on follow the scheme. Tailwind isn't compiled through Sass, so keep Tailwind directives in `.css` files.
