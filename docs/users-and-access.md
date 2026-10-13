# Users and Access Control

Enterfind's access model is deliberately small: authentication is optional, and when it's on there are two levels — **READER** and **ADMIN**. There are no tiers or roles beyond that. This page covers when you need authentication, what the two levels mean, and the two kinds of credential (human users and API keys).

## Authentication is optional

An Enterfind instance can run with authentication **off** or **on**. There is no setting for it: authentication turns on when the first human user is created, and that first user is always an ADMIN. API keys can only be created once authentication is on.

- **Off** — anyone who can reach the instance can use it, at full ADMIN capability. This is reasonable for a single-user instance on a trusted local network.
- **On** — callers must authenticate, and what they can do depends on their level.

An instance with authentication off and [appliance mode](installation.md#configuration-the-environment-variables) on is anonymous but read-only. That is how the public demo runs.

An instance reports whether authentication is required, so a client (including an AI assistant) can discover up front whether it needs credentials.

Turning authentication on matters most before you enable [Remote Access](remote-access.md): once an instance is reachable from the public internet, you almost certainly want authentication on so that reaching the URL isn't the same as having full access.

## The two levels

- **READER** — can search and read documents. This is the level for people (or assistants) who consume the corpus but shouldn't change how it's built.
- **ADMIN** — everything a reader can do, plus configuring data sources and treatments, managing users and API keys, and changing instance settings.

An admin sets up and maintains the instance; readers use it. For a household or team, that typically means one or two admins and everyone else a reader.

## Human users

A human user is a person's login. Enterfind uses **time-based one-time codes (TOTP)** — the six-digit codes from an authenticator app — rather than passwords, plus **recovery codes** for when the authenticator isn't available.

Setting up a user works by invitation:

1. An admin creates the user, choosing their nickname and level (READER or ADMIN). This produces a one-time **setup link**.
2. The new user opens the setup link, which shows a QR code to add Enterfind to their authenticator app, along with their recovery codes to save somewhere safe.
3. From then on, they log in with their nickname and the current code from their app.

Admins can update a user's level, deactivate a user, regenerate a user's TOTP secret or recovery codes, and re-issue a setup link if the first went unused. A user can log in with a recovery code if they've lost access to their authenticator.

## API keys

An API key authenticates a **non-interactive** caller — a script, an integration, or an AI assistant — that can't complete an interactive TOTP login. Like human users, an API key carries a level (READER or ADMIN), so you can hand out a read-only key for search without granting configuration rights.

Key facts about API keys:

- An admin creates a key with a nickname, a level, and an expiration. Enterfind shows the full key value **once**, at creation — store it then; afterward only a preview is visible.
- A caller exchanges the key for a session token and uses that token for subsequent requests. The mechanics are on [The API](api.md) page.
- Keys can be deleted, and they expire on their own schedule; both are ways to bound their reach.

For connecting AI assistants specifically, a READER-level key is usually the right choice — it lets the assistant search and retrieve without being able to alter your sources or settings. See [Connecting AI Assistants](ai-access.md).

## Choosing a posture

- **Single user, local network, nothing sensitive** — authentication off is fine.
- **Multiple people** — authentication on; admins for the people who maintain it, readers for everyone else.
- **Reachable remotely** — authentication on, and prefer READER-level API keys for automated/AI access so a leaked key can't reconfigure the instance.

---

**See also:** [Connecting AI Assistants](ai-access.md) · [Remote Access](remote-access.md) · [The API](api.md)

[← Back to documentation index](../README.md)
