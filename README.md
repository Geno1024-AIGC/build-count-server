# build-count-server

A tiny public HTTP service that counts builds per **project → module → day**.

This service only owns the **count** side of the org's build-number convention
(`RULES/VERSION.md`). It does not issue or parse `<major>.<minor>.<env>.<pack>.<sha8>`
version stamps: consuming projects decide how to combine a count from here with
their own base / sha. The service itself deliberately has no build stamp and no
`dist.sh` — keep it small.

It is a breaking rewrite of the 7-year-old version: the old `GET /{project}` both
displayed and incremented the counter. Here **GET is always read-only** and
**POST records a build**; no old data is migrated, counters start at zero.

## API

All paths are RESTful. `GET` is public and read-only; only `POST` needs a token.

| Method | Path | Response |
|---|---|---|
| `GET` | `/healthz` | `200` `text/plain` `ok` |
| `GET` | `/projects` | `200` JSON `["qqfm", ...]` |
| `GET` | `/projects/{project}` | `200` JSON `{"name":"qqfm","total":123}` |
| `GET` | `/projects/{project}/modules` | `200` JSON `["app", ...]` |
| `GET` | `/projects/{project}/modules/{module}` | `200` JSON `{"project":"qqfm","module":"app","total":42}` |
| `GET` | `/projects/{project}/calendar` | `200` JSON `{"2026-11-19":42}` |
| `GET` | `/projects/{project}/modules/{module}/calendar` | `200` JSON `{"2026-11-19":30}` |
| `POST` | `/projects/{project}/modules/{module}/builds` | `201` `text/plain` → new module total |

- Every `POST` increments unconditionally. Retries and CI re-runs each count as
  a build; there is no idempotency key and no rate limit.
- `POST` requires `Authorization: Bearer <BUILD_COUNT_TOKEN>`; missing/invalid
  token gets `401`, other methods on a route get `405`.
- `project` / `module` must match `[A-Za-z0-9._-]{1,64}` (no `.` or `..`), which
  also prevents path traversal into the data directory.
- A project/module is created implicitly on its first `POST`.
- Dates use **Asia/Shanghai** (`yyyy-MM-dd`). Every response has
  `Cache-Control: no-store`.

Example:

```sh
curl -X POST https://count.example.com/projects/qqfm/modules/app/builds \
  -H "Authorization: Bearer $BUILD_COUNT_TOKEN"
# 42

curl https://count.example.com/projects/qqfm/modules/app/calendar
# {"2026-11-19":30}
```

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `BUILD_COUNT_TOKEN` | *(required)* | Shared bearer token for all `POST`s; server exits if unset |
| `BIND` | `127.0.0.1` | Listen address; put a TLS reverse proxy in front for public traffic |
| `PORT` | `8080` | Listen port |
| `DATA_DIR` | `data` | Directory holding one `.properties` file per project |

## Storage

One JDK `Properties` file per project (`data/<project>.properties`):

```properties
total=123
module.app=80
module.git-core=43
day.2026-11-19=123
day.2026-11-19.module.app=80
```

Invariants: `total == sum(module.*)` and `day.D == sum(day.D.module.*)`.
A write takes a `FileLock` on the project file, updates all four keys, then
publishes via an atomic rename; readers never see a partial file and concurrent
builds cannot lose an increment. Backup is just copying the data directory.

## Build, test, run

Requires JDK 21+, no external dependencies or build tool.

```sh
./test.sh                        # dependency-free self tests
BUILD_COUNT_TOKEN=secret ./run.sh
```

## Deploy

`deploy/build-count-server.service` and `deploy/Caddyfile.example` show a
minimal setup: the JVM listens on loopback, Caddy terminates TLS and proxies.
Keep the token in a root-owned `0600` `EnvironmentFile`, never in this repo.

The data directory is runtime state (`.gitignore` excludes `data/`); back it up
separately. No migration tool exists for the old counter files.
