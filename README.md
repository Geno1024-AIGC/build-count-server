# build-count-server

A tiny public HTTP service that counts builds per **project → module → day**.

This service owns the **`pack`** side of the org's build-number convention
(`RULES/VERSION.md`): a packaging build POSTs here and uses the returned module
total as `pack`. It does not handle `major.minor`, `env`, or `sha8`: consuming
projects combine the count with their own Git-derived fields. The service itself
deliberately has no five-part build stamp and no `dist.sh` — keep it small.

Standard deployment: `http://build-count.invalid`.

It is a breaking rewrite of the 7-year-old version: the old `GET /{project}` both
displayed and incremented the counter. Here **GET is always read-only** and
**POST records a build**; no old data is migrated, counters start at zero.

## Runtime

- **Java 8 or newer.** The classes are compiled for Java 8, so the same `out/main`
  runs on 8/11/17/21.
- Building needs a JDK (8+). If the server only has a JRE, run `./build.sh`
  locally and copy `out/main` over.
- Plain HTTP only. Put it on whatever port you like; there is no TLS termination
  in this repo.

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
curl -X POST http://your-server:8080/projects/qqfm/modules/app/builds \
  -H "Authorization: Bearer $BUILD_COUNT_TOKEN"
# 42

curl http://your-server:8080/projects/qqfm/modules/app/calendar
# {"2026-11-19":30}
```

> The bearer token travels in plaintext over HTTP. Use it on a trusted network,
> and restrict the port with a firewall / security group if possible.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `BUILD_COUNT_TOKEN` | *(required)* | Shared bearer token for all `POST`s; server exits if unset |
| `BIND` | `127.0.0.1` | Listen address; use `0.0.0.0` for direct public access |
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

Requires JDK 8+, no external dependencies or build tool.

```sh
./test.sh                        # dependency-free self tests
./package.sh                     # builds out/build-count-server.jar
BUILD_COUNT_TOKEN=secret BIND=0.0.0.0 java -jar out/build-count-server.jar
```

## Deploy

`deploy/build-count-server.service` is a minimal systemd unit: it binds
`0.0.0.0:8080`, runs as `buildcount`, and reads the token from
`/etc/build-count-server.env` (root-owned, `0600`, never in this repo).

```sh
sudo useradd --system --home /var/lib/build-count-server \
  --shell /usr/sbin/nologin buildcount
sudo install -d -o buildcount -g buildcount /var/lib/build-count-server
sudo install -d /opt/build-count-server

# build locally (or on the server if it has a JDK) and place the jar
./package.sh
sudo install -m 644 out/build-count-server.jar /opt/build-count-server/build-count-server.jar

echo "BUILD_COUNT_TOKEN=$(openssl rand -hex 32)" | sudo tee /etc/build-count-server.env
sudo chmod 600 /etc/build-count-server.env

sudo cp deploy/build-count-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now build-count-server
```

Directory and token:

| Path | Purpose |
|---|---|
| `/opt/build-count-server/build-count-server.jar` | the runnable jar |
| `/var/lib/build-count-server/` | `DATA_DIR`; one `<project>.properties` per project, owned by `buildcount` |
| `/etc/build-count-server.env` | `BUILD_COUNT_TOKEN=...`; root-owned `0600`, read by systemd |

The unit already sets `BIND=0.0.0.0`, `PORT=8080`, and
`DATA_DIR=/var/lib/build-count-server`. To run by hand instead:

```sh
set -a; . /etc/build-count-server.env; set +a
BIND=0.0.0.0 PORT=8080 DATA_DIR=/var/lib/build-count-server \
  java -jar /opt/build-count-server/build-count-server.jar
```

Open the port (example with ufw; ideally restrict the source):

```sh
sudo ufw allow from <trusted-ip> to any port 8080 proto tcp
```

The data directory is runtime state (`.gitignore` excludes `data/`); back it up
separately. No migration tool exists for the old counter files.
