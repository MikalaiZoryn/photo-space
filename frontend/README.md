# frontend

The React 19, TypeScript, and Vite web app. It uses Vitest with Testing Library for tests and oxlint for linting.

In development, `npm run dev` proxies `/api` to the backend at `http://localhost:8080`. Override the target with `VITE_API_PROXY_TARGET`. The browser only ever talks to its own origin, so no CORS configuration is needed. The proxy keeps the browser's `Host` header (`changeOrigin: false`), so the backend builds the Google redirect URI and the post-sign-in redirect for `localhost:5173`.

API calls go through `src/api/client.ts`. It sends the session cookie and adds the CSRF header to state-changing requests. Each resource has a module next to it (`src/api/clients.ts`, `src/api/sessions.ts`).

Inside a workspace, the page is kept in the URL hash (`#/sessions`, `#/clients/{id}/edit`, …) and parsed by `src/routing.ts`. There is no router dependency. Pages load data with `src/useLoad.ts`, which aborts stale requests and drives loading, error, and retry states. Session times are formatted in the workspace time zone by `src/time.ts`, never in the browser's own zone.

| Command | What it does |
| --- | --- |
| `npm run dev` | Dev server on http://localhost:5173 |
| `npm run lint` | oxlint, with warnings treated as errors |
| `npm run typecheck` | `tsc -b` |
| `npm test` | Vitest, single run |
| `npm run build` | Typecheck and production build to `dist/` |
