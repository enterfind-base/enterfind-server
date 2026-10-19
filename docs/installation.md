# Installation

Enterfind is a single Spring Boot application backed by a PostgreSQL database. There are three ways to run it:

- **[Installer](#installer-no-docker)** — for a Windows, macOS or Linux computer without Docker. One program sets up everything in your home folder.
- **[Using Docker](#using-docker)** — for a server, a NAS, or anywhere Docker runs: the image on its own, or one of several Compose setups.
- **[Building from source](#building-from-source)** — for development, or to run the jar yourself.

## What Enterfind needs

- **PostgreSQL** — a database and a user. The minimal Compose setup runs Postgres 16; the app talks standard JDBC, so a managed or external Postgres works equally well.
- **A JVM to run it** — Java 25, if you run the jar directly. The Docker image already bundles a Temurin 25 JRE, so you only need Java yourself when running from source or from a bare jar.
- **Tesseract** — only for the [OCR](treatment-ocr.md) treatment. The Docker image bundles Tesseract with models for every script OCR supports: English, French, German, Spanish, Hebrew, simplified Chinese, Japanese, Korean, Bengali, Tamil and Thai, plus the Cyrillic, Arabic, Greek, Devanagari, Telugu and Kannada script models — see [Supported scripts](treatment-ocr.md#supported-scripts). If you run the jar directly and want OCR, install Tesseract (and the language packs you need) on the host.

Object and face detection run on ONNX Runtime, which is bundled in the jar — no separate install.

The installer brings all of this with it: Java, PostgreSQL and Tesseract with the OCR models above, and it generates the passwords and the encryption key.

---

## Installer (no Docker)

For a computer without Docker — Windows 11, macOS (Apple silicon or Intel), Linux or WSL2. The [Enterfind installer](https://github.com/enterfind-base/enterfind-installer) is one program you download and run in a terminal: it installs the server, a Java runtime, PostgreSQL and Tesseract into `~/.enterfind`, with no administrator rights and no package manager, then keeps Enterfind running in the background and, by default, starts it again after a restart.

- **Install:** the one-line command for your system is in the installer's guide, together with the browser download, the requirements and every command.
- **Settings are the installer's.** It generates the database passwords and `ENTERFIND_ENCRYPTION_KEY`, picks the port (8080 or the next free one), and sizes Java and PostgreSQL to the computer's memory. The [environment variables](#configuration-the-environment-variables) do not apply; change settings with `enterfind config`.
- **This computer only, by default.** Enterfind listens on `127.0.0.1` unless you choose otherwise during install or with `enterfind config --bind all`. For access from elsewhere, prefer [Remote Access](remote-access.md).
- **Behind a proxy.** If the computer reaches the internet only through an HTTP proxy, the install picks it up (the terminal's `HTTPS_PROXY`, or on Windows and macOS the computer's proxy settings) and records it. The server then uses it for Remote Access, model downloads, cloud AI providers and S3, including when autostart starts it after a restart. Change it with `enterfind config --proxy`, and list hosts on your own network (an Ollama server, a MinIO store) with `--proxy-bypass`. The installer's guide, under [Behind a proxy](https://github.com/enterfind-base/enterfind-installer#behind-a-proxy), covers the details.
- **Local folders directly.** A Local Filesystem data source points at a folder's real path — no bind mounts.
- **Updates** come through `enterfind update`, within the installed major version.

The installer is released separately from the server, as MIT-licensed binaries; Enterfind itself remains AGPLv3.

---

## Using Docker

Enterfind publishes a multi-architecture image (linux/amd64 and linux/arm64, so it runs on Intel/AMD servers and on ARM including Apple Silicon and most modern NAS boxes) to the GitHub Container Registry. For a rundown of which ARM SBCs are and aren't compatible, see [ARM Hardware Compatibility](arm-hardware.md). Images are named **per stage**:

```
ghcr.io/enterfind-base/enterfind-server-beta:latest
```

The `-beta` suffix is the stage. Pin a specific build with its version tag (for example `…-beta:1.0.123`) instead of `latest` if you want reproducible deploys.

The image needs a PostgreSQL database and is configured through [environment variables](#configuration-the-environment-variables). The setups below differ only in how much they put around it:

| Setup | What it runs |
|---|---|
| [The image alone](#the-image-alone) | the app container, against a Postgres you already have |
| [Compose: minimal](#compose-minimal) | app + Postgres — the common case |
| [Compose: external PostgreSQL](#compose-external-postgresql) | app only, against a managed or existing Postgres |
| [Compose: behind your own proxy](#compose-behind-your-own-proxy) | the minimal setup behind a proxy or tunnel you already run |
| [Compose: Traefik reference stack](#compose-traefik-reference-stack) | app + Postgres + Traefik with Let's Encrypt via AWS Route53 + Watchtower |
| [Automatic updates](#automatic-updates-watchtower) | Watchtower, added to any Compose setup |

### The image alone

To run just the app container against a Postgres you already have:

```bash
docker run -d \
  --name enterfind \
  -p 8080:8080 \
  -e DB_URL="jdbc:postgresql://your-db-host:5432/enterfind" \
  -e DB_USERNAME="enterfind" \
  -e DB_PASSWORD="your-db-password" \
  -e ENTERFIND_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  -v "$PWD/logs:/app/logs" \
  ghcr.io/enterfind-base/enterfind-server-beta:latest
```

Then open `http://localhost:8080`. To index a local folder, bind-mount it into the container (for example `-v /home/me/documents:/data/documents:ro`) and point a Local Filesystem data source at the in-container path (`/data/documents`) — see [Data Sources](datasources.md).

### Compose: minimal

App + Postgres, no proxy, no domain, no AWS: run it on your LAN or behind a proxy you already operate, reachable on a port.

This one ships as ready-to-use files — **`docker-compose.minimal.yml`** and **`.env.minimal.template`** at the project root — rather than a snippet to copy out of this page:

```bash
cp .env.minimal.template .env   # then fill in DB_PASSWORD and ENTERFIND_ENCRYPTION_KEY
docker compose -f docker-compose.minimal.yml up -d
```

It wires up just `postgres` and `enterfind`, with a bind-mount for logs and a commented-out example for mounting the documents you want to index. Reach it at `http://<host>:8080`. For access from outside your network, enable [Remote Access](remote-access.md) — that gives you a public URL through Enterfind's tunnel without any proxy, TLS or AWS machinery.

### Compose: external PostgreSQL

If you run Postgres already — a managed instance (RDS, Cloud SQL, Supabase, a Postgres on another host) — drop the Postgres service entirely and point the app at it. Enterfind only needs a database and a user with rights on it.

```yaml
services:
  enterfind:
    image: ghcr.io/enterfind-base/enterfind-server-beta:latest
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      DB_URL: jdbc:postgresql://db.example.com:5432/enterfind
      DB_USERNAME: enterfind
      DB_PASSWORD: ${DB_PASSWORD}
      ENTERFIND_ENCRYPTION_KEY: ${ENTERFIND_ENCRYPTION_KEY}
    volumes:
      - ./logs:/app/logs
```

Notes:
- If the managed provider requires TLS, append the driver's SSL parameters to the JDBC URL, e.g. `…/enterfind?sslmode=require`.
- Create the database and user out-of-band (the app doesn't create the database itself; the bundled Postgres container did that via its init variables, which you've now removed).
- This same shape works with a Postgres running directly on the host — use `host.docker.internal` (Docker Desktop) or the host's LAN IP as the JDBC host.

### Compose: behind your own proxy

If you already terminate TLS with nginx, Caddy, Traefik, or a tunnel (Cloudflare Tunnel, Tailscale), run the [minimal setup](#compose-minimal), **don't** publish port 8080 to the internet, and put your proxy in front of `http://enterfind:8080` (or the host port). Enterfind serves plain HTTP; your proxy handles the certificate.

### Compose: Traefik reference stack

The repository ships **`docker-compose.traefik-aws.yml`** at the project root — named for what it is: a Traefik-fronted deployment whose Let's Encrypt certificates come from an AWS Route53 DNS challenge. It wires up the app, Postgres, Traefik terminating TLS, and Watchtower for automatic image updates: the stack for a public, domain-fronted deployment.

It is opinionated — it assumes you own a domain and have AWS credentials for the DNS challenge, and it pulls the image from a private registry via `REGISTRY_URL`. Copy `.env.traefik-aws.template` to `.env` and fill in the domain, AWS and registry values. Most self-hosters want something smaller; read this file to see how the pieces fit even if you run one of the setups above.

> **Image source note.** The file points at `${REGISTRY_URL}/enterfind-server:latest-${SPRING_PROFILE}`, which defaults to a placeholder private registry. For a public install, replace that image with the GHCR image — `ghcr.io/enterfind-base/enterfind-server-${SPRING_PROFILE}:latest`.

### Automatic updates (Watchtower)

To get the reference stack's auto-update behavior without the rest, add a Watchtower service to any Compose setup above. It watches running containers and pulls new images on a schedule:

```yaml
  watchtower:
    image: containrrr/watchtower:latest
    restart: unless-stopped
    environment:
      WATCHTOWER_POLL_INTERVAL: 3600
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
```

With `latest`-tagged images this keeps you current automatically; with pinned version tags it does nothing (which may be what you want in production — pin, and update deliberately).

### Which Docker setup

- **Home / LAN, keep it simple** → [Compose: minimal](#compose-minimal), with [Remote Access](remote-access.md) for outside access.
- **Already have a database** → [Compose: external PostgreSQL](#compose-external-postgresql).
- **Already have a proxy or tunnel** → [Compose: behind your own proxy](#compose-behind-your-own-proxy).
- **Public, domain-fronted, batteries included** → the [Traefik reference stack](#compose-traefik-reference-stack) as-is.
- Mix freely: external Postgres + your own proxy + Watchtower is a perfectly normal production shape.

---

## Building from source

For development, or to run the jar without Docker and without the installer.

### Prerequisites

- **Java 25** (JDK) — the project targets Java 25.
- **Maven** — to build. (The repo also supports a devcontainer-based flow; see below.)
- **PostgreSQL** — running and reachable.
- **Tesseract** — only if you want OCR (see *What Enterfind needs* above).

### Build

The Maven build produces a single executable Spring Boot jar named `enterfind-server.jar` in `target/`:

```bash
mvn clean package
```

To skip tests during a quick build, add `-DskipTests`. To compile without packaging, `mvn clean compile`; to run the test suite, `mvn test`.

### Run

Run the packaged jar directly, supplying configuration as arguments or environment variables:

```bash
java -jar target/enterfind-server.jar \
  --spring.profiles.active=beta \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/enterfind \
  --spring.datasource.username=enterfind \
  --spring.datasource.password=enterfind \
  --enterfind.encryption.key="$(openssl rand -base64 32)"
```

Or run it straight from Maven during development:

```bash
mvn spring-boot:run
```

Either way it comes up on `http://localhost:8080` (override with `--server.port=...` or `SERVER_PORT`). The [environment variables](#configuration-the-environment-variables) apply as well; the `--spring.datasource.*` and `--enterfind.*` flags shown here are what the container's entrypoint sets for you under the hood.

### Devcontainer workflow

The repository includes a `.devcontainer` and a helper script, `dev.sh`, that drives a Dev Container CLI so builds run in a container matching CI. With the Dev Container CLI installed (`npm install -g @devcontainers/cli`), common tasks are:

```bash
./dev.sh package     # build the jar inside the devcontainer
./dev.sh test        # run tests
./dev.sh run         # run the app
./dev.sh shell       # open a shell in the devcontainer
```

This is optional — a local Java 25 + Maven install builds the same jar — but it's the path CI uses, so it's the most faithful reproduction of the official build.

### Building your own Docker image

The `Dockerfile` builds the runtime image from a **prebuilt jar**: it expects `target/enterfind-server.jar` to exist (so run `mvn package` first), then assembles an Ubuntu + Temurin 25 JRE + Tesseract image and trims unused native libraries. Build a multi-arch image the way CI does with Buildx:

```bash
mvn package
docker buildx build --platform linux/amd64,linux/arm64 -t my-enterfind:local .
```

For a single-arch local image, drop the `--platform` flag.

---

## Configuration: the environment variables

For Docker and for running the jar yourself; the installer manages its own settings. Every runtime option is an environment variable, read at startup. These are the ones that matter, with their defaults:

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `beta` | Which Spring profile to run. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/enterfind` | JDBC URL of the database. |
| `DB_USERNAME` | `enterfind` | Database user. |
| `DB_PASSWORD` | `enterfind` | Database password. |
| `ENTERFIND_ENCRYPTION_KEY` | *(empty)* | AES-256 key (base64, 32 bytes) that encrypts stored data-source credentials. **If empty, those credentials are stored in plaintext.** Generate with `openssl rand -base64 32`. |
| `ENTERFIND_APPLIANCE_MODE` | `false` | When `true`, runs in appliance mode: mutations and background state changes are disabled (used for read-only/demo instances). Leave `false` for a normal install. |
| `SERVER_PORT` | `8080` | HTTP port the app listens on. |
| `PUID` | `998` | (Docker image only) UID the app process runs as, so mounted volumes get sensible ownership. Set it to your host user's UID if you bind-mount writable paths. |
| `JAVA_OPTS` | *(tuned G1GC defaults)* | (Docker image only) JVM flags. The default sets container-aware heap sizing; override only if you know why. |

Set `ENTERFIND_ENCRYPTION_KEY` before you add any data source whose credentials you care about — it's what keeps SMB and S3 secrets from sitting in the database as plaintext.

---

## After it's running

- Open the web UI and create your first [data source](datasources.md), then scan it.
- Decide on [users and access control](users-and-access.md) — turn authentication on before exposing the instance.
- To reach it from outside your network or connect a cloud AI assistant, enable [Remote Access](remote-access.md).
- Once it's indexing, [Operations](operations.md) covers where the logs are, checking on scans, backups, and what an upgrade does to the schema.
- New here? [Getting Started](getting-started.md) walks the whole path from zero to first search.
