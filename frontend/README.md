# frontend

The React 19, TypeScript, and Vite web app, styled with Tailwind CSS and shadcn/ui. It uses Vitest with Testing Library for tests and oxlint for linting.

In development, `npm run dev` proxies `/api` to the backend at `http://localhost:8080`. Override the target with `VITE_API_PROXY_TARGET`. The browser only ever talks to its own origin, so no CORS configuration is needed. The proxy keeps the browser's `Host` header (`changeOrigin: false`), so the backend builds the Google redirect URI and the post-sign-in redirect for `localhost:5173`.

API calls go through `src/api/client.ts`. It sends the session cookie and adds the CSRF header to state-changing requests. Each resource has a module next to it (`src/api/clients.ts`, `src/api/sessions.ts`).

Inside a workspace, the page is kept in the URL hash (`#/sessions`, `#/clients/{id}/edit`, …) and parsed by `src/routing.ts`. There is no router dependency. Pages load data with `src/useLoad.ts`, which aborts stale requests and drives loading, error, and retry states. Session times are formatted in the workspace time zone by `src/time.ts`, never in the browser's own zone.

## Styling

Styles use Tailwind CSS v4 (configured in `src/index.css`, no `tailwind.config.js`) and shadcn/ui components in `src/components/ui/`. See `docs/decisions.md` D15.

- Colors, radii, and status tones are CSS variables at the top of `src/index.css`, with a dark set under `prefers-color-scheme: dark`. Change the theme there, not in components.
- App building blocks live in `src/ui/` (`PageHeader`, `Panel`, `RecordList`, `Field`, `StatusBadge`, `Feedback`, `Photo`) and the layouts in `src/layout/` (`AppShell` with the sidebar, `CenteredLayout`).
- Add a shadcn component with `npx shadcn@latest add <name>` from `frontend/` (the CLI is not a project dependency), then review the generated file. If it uses a `data-*:` variant other than `data-open` or `data-closed`, copy that variant from the CLI's `tailwind.css` into `src/index.css`. Keep selects, checkboxes, and file inputs native.
- Import app code with `@/…` for `src/…` (configured in `vite.config.ts` and `tsconfig.app.json`).

| Command | What it does |
| --- | --- |
| `npm run dev` | Dev server on http://localhost:5173 |
| `npm run lint` | oxlint, with warnings treated as errors |
| `npm run typecheck` | `tsc -b` |
| `npm test` | Vitest, single run |
| `npm run build` | Typecheck and production build to `dist/` |
