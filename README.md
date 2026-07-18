# jw-schedule-backend

A Catalyst **AppSail** (Spring Boot, Java 17) with two jobs:

1. **HTML → PDF** via Catalyst's managed **SmartBrowz** (real server-side Chrome) — so Tamil
   (Noto Sans Tamil) shaping is perfect and the output is byte-for-byte identical on every device.
   No html2canvas, no client print, no Selenium grid.
2. **Data layer** — congregations + per-user access/permissions in `TJW_*` tables; each schedule
   document (opaque JSON per kind) is stored in a **Stratus bucket**, with only its object PATH kept
   in `TJW_Data`. This is the drop-in replacement for the front-end's `localStorage`.

The code is complete and compiles; the remaining work is on the Catalyst side (create tables, set
env, deploy) — see the checklist at the end.

## Endpoints

All schedule endpoints identify the caller via the **`X-User-Email`** header (set by the front-end
from Google sign-in). See the trust note in `AccessGuard`.

**PDF**

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Health check (JSON) |
| GET | `/pdf/sample` | Built-in Tamil CLM sheet → PDF. **Open in a browser to confirm rendering.** |
| POST | `/pdf` | Body `{ "html": "<full doc>", "landscape": true, "format": "A4", "filename": "..." }` → PDF |

**Data / access** (all require `X-User-Email`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/me` | Congregations the user can access + their permissions |
| POST | `/congregations` | `{ "name": "..." }` → creates a congregation; caller becomes owner (full access) |
| GET | `/congregations/{id}` | Congregation meta (members only) |
| GET | `/congregations/{id}/access` | List access entries (**owner only**) |
| POST | `/congregations/{id}/access` | `{ "email": "...", "permissions": {...} }` grant/update (**owner only**) |
| DELETE | `/congregations/{id}/access?targetEmail=...` | Revoke (**owner only**) |
| GET | `/congregations/{id}/data` | All schedule docs the caller may view: `{ kind: <json>, ... }` |
| GET | `/congregations/{id}/data/{kind}` | One doc (requires view on that area) |
| PUT | `/congregations/{id}/data/{kind}` | Body = JSON doc; upsert (requires edit on that area) |

`kind` ∈ `publishers, groups, clm, weekend, av, cleaning, fsm, attendant, meta`.
Permission areas ∈ `clm, weekend, av, cleaning, fsm, attendant`, each with independent `view`/`edit`.
`publishers`/`groups`/`meta` are shared reference data (view = membership; edit = edit on any area,
or owner).

## Tables to create (prefix `TJW_`)

Column **names are case-sensitive** and must match exactly. `ROWID`/`CREATORID`/timestamps are added
by Catalyst automatically.

**`TJW_Congregation`**

| Column | Type |
|---|---|
| `NAME` | Varchar |
| `CODE` | Varchar |
| `OWNER_EMAIL` | Varchar |
| `CREATED_AT` | BigInt |

**`TJW_Access`**

| Column | Type |
|---|---|
| `CONGREGATION_ID` | BigInt |
| `EMAIL` | Varchar |
| `PERMISSIONS` | Text (large — holds a JSON object) |
| `CREATED_AT` | BigInt |

**`TJW_Data`** — index only; the JSON document itself lives in a **Stratus bucket**, not the DB.

| Column | Type |
|---|---|
| `CONGREGATION_ID` | BigInt |
| `KIND` | Varchar |
| `PATH` | Varchar (255) — Stratus object key, e.g. `congregations/{id}/clm.json` |
| `UPDATED_AT` | BigInt |

**`TJW_Token`** — shared access-token cache (one fixed row) so instances/restarts reuse one token.

| Column | Type |
|---|---|
| `NAME` | Varchar (32) — fixed key `catalyst`; mark Unique |
| `ACCESS_TOKEN` | Text (mark Encrypted for at-rest protection) |
| `EXPIRES_AT` | BigInt |

Also **create a Stratus bucket** (any name) for the schedule documents, and set its name as
`TJW_STRATUS_BUCKET`.

Then set the numeric ids as env vars: `TJW_CONGREGATION_TABLE_ID`, `TJW_ACCESS_TABLE_ID`,
`TJW_DATA_TABLE_ID`, `TJW_TOKEN_TABLE_ID`.

## How the app makes the PDF

`PdfService` → `Catalyst.smartBrowz()` → `ZCSmartBrowz.convertToPdf(html, pdfOptions, null, navOptions)`.

The SDK authenticates **internally** from the project's OAuth config (the env vars below) — our code
never handles an access token. Options sent: `landscape`, `print_background=true`, `format=A4`, zero
margins, and `navigation.waitUntil=networkidle0` so Noto Sans Tamil finishes loading before the
print. A full HTML-document string is treated as HTML (not a URL).

### Optional: confirm SmartBrowz with a raw curl (no build/deploy)

If you want to sanity-check the service by itself, `scripts/test-smartbrowz.sh` hits the REST endpoint
directly. Raw HTTP has no SDK to auth for it, so this one needs a console OAuth token
(scope `ZohoCatalyst.pdfshot.execute`) — the app itself does **not**:

```bash
export PROJECT_ID=... ACCESS_TOKEN=...   # token only needed for the standalone curl test
./scripts/test-smartbrowz.sh             # -> out.pdf
```

## Credentials (env vars — same set as hello-app)

Set these on the AppSail (and your shell for local runs). The SDK uses them to build the project and
authenticate; scope `ZohoCatalyst.pdfshot.execute` is required.

Required: `CLIENT_ID`, `CLIENT_SECRET`, `REFRESH_TOKEN`, `PROJECT_ID`, `PROJECT_KEY`
Optional: `PROJECT_DOMAIN` (default `https://api.catalyst.zoho.in`), `ENVIRONMENT` (default
`Development`), `PROJECT_NAME` (default `JW-Schedule`).
Table ids (after you create the tables): `TJW_CONGREGATION_TABLE_ID`, `TJW_ACCESS_TABLE_ID`,
`TJW_DATA_TABLE_ID`.

OAuth scopes: `ZohoCatalyst.pdfshot.execute` (PDF) + `ZohoCatalyst.tables.READ`,
`ZohoCatalyst.tables.rows.READ/CREATE/UPDATE/DELETE` (data store) +
`ZohoCatalyst.buckets.READ`, `ZohoCatalyst.buckets.objects.READ/CREATE/UPDATE/DELETE` (Stratus).

Data-centre URLs (India `.in` defaults; already set for `bootRun`):
`X_ZOHO_CATALYST_ACCOUNTS_URL`, `X_ZOHO_CATALYST_CONSOLE_URL`.

## Build

```bash
./gradlew clean bootJar
# -> build/libs/jw-schedule-backend-1.0.jar   (matches app-config.json build_path)
```

## Run locally

```bash
export CLIENT_ID=... CLIENT_SECRET=... REFRESH_TOKEN=... PROJECT_ID=... PROJECT_KEY=...
./gradlew bootRun
# then open http://localhost:3000/pdf/sample
```

## Deploy to Catalyst

`app-config.json` is already set (`stack: java17`, `command: java -jar ...`, `memory: 512`).
From your Catalyst project (with this as the AppSail source dir):

```bash
catalyst deploy        # or: catalyst deploy --only appsail
```

## Your checklist (Catalyst side — do this last)

1. **Create the 4 tables** `TJW_Congregation`, `TJW_Access`, `TJW_Data`, `TJW_Token` with the columns
   above (exact, case-sensitive names).
2. **Create a Stratus bucket** for the schedule documents; set its name as `TJW_STRATUS_BUCKET`.
3. **Enable SmartBrowz** in the project (PDF & Screenshot).
4. **Note the numeric table ids** and set them as env vars on the AppSail:
   `TJW_CONGREGATION_TABLE_ID`, `TJW_ACCESS_TABLE_ID`, `TJW_DATA_TABLE_ID`, `TJW_TOKEN_TABLE_ID`.
5. **Set the OAuth env vars** (`CLIENT_ID`, `CLIENT_SECRET`, `REFRESH_TOKEN`, `PROJECT_ID`,
   `PROJECT_KEY`, + optional `PROJECT_DOMAIN`/`ENVIRONMENT`/`PROJECT_NAME`) with the scopes listed above.
6. **Build & deploy**: `./gradlew clean bootJar` then `catalyst deploy`.
7. **Confirm PDF**: open `https://<app>/pdf/sample` → Tamil CLM sheet renders correctly.
8. **Confirm data**: 
   ```bash
   curl -X POST https://<app>/congregations -H 'X-User-Email: you@example.com' \
        -H 'Content-Type: application/json' -d '{"name":"Urapakkam Tamil"}'
   curl https://<app>/me -H 'X-User-Email: you@example.com'
   ```
