# Connecting AI Assistants

Enterfind is built so AI assistants can search and retrieve your documents through the same API the web UI uses. This page covers how to connect the major assistants and — importantly — the **two different ways** an assistant can reach an Enterfind instance.

## Two ways to connect over MCP

Assistants that speak **MCP** (the Model Context Protocol) — Claude and the Gemini CLI — can reach Enterfind by either of two routes. Pick based on where the assistant runs and whether the instance is exposed to the internet.

### 1. Remote proxy URL (hosted / cloud assistants)

When the assistant runs in the cloud (claude.ai, a Claude custom connector, ChatGPT), it has to reach your instance over the public internet. Enterfind provides this through its own outbound **tunnel**: enable [Remote Access](remote-access.md) and your instance gets a stable public URL without you opening any ports. The MCP endpoint is then:

```
https://<serverId>.remote-<stage>.enterfind.com/mcp
```

Your Server ID and full remote URL appear on the Settings page under Remote Access. You paste this one URL into the assistant; nothing runs on your side beyond Enterfind itself. This is the path for any assistant not running on your own machine.

To see this route working before you set up Remote Access, connect the assistant to the public read-only demo at `https://b6b5b23e3ac8.remote-beta.enterfind.com/mcp`. It needs no API key.

The tunnel is a hosted service operated by Enterfind's creators and is subject to availability and possible throttling — see [Remote Access](remote-access.md) for what that means.

### 2. Local MCP server (assistants on the same machine/network)

When the assistant runs on your own computer — notably **Claude Desktop** — it can talk to Enterfind's GraphQL API directly, with no tunnel and no public exposure, using a small local wrapper. Enterfind ships a Node.js script (`mcp-graphql-wrapper.js`, downloadable from the instance) that implements MCP locally and translates the assistant's calls straight to your instance's `/graphql` endpoint.

You configure the assistant to launch the wrapper against your instance's GraphQL URL — for example `http://localhost:8080/graphql` or a LAN address like `http://192.168.1.100:8080/graphql`. For Claude Desktop this is an entry in its MCP servers configuration pointing `node` at the wrapper script and your GraphQL URL. The instance's own **Local MCP Setup** page (Settings → MCP Server) walks through the exact configuration file location per platform and includes a one-line connection test. The same page covers Claude Code and the Gemini CLI against the instance's local `/mcp` URL, with the API key header.

This route is for **local or trusted-network** use — it's designed for a machine that can already reach the instance directly, and it doesn't require Remote Access. If the assistant is in the cloud, use the remote proxy URL instead.

### Which one?

- Assistant runs in the cloud (claude.ai, ChatGPT) → **remote proxy URL** (requires Remote Access).
- Assistant runs on your machine and can reach the instance directly (Claude Desktop on the same box or LAN) → **local MCP server** (no Remote Access needed).

Both expose the same Enterfind capabilities; they differ only in the path to your instance.

## How authentication works with assistants

If the instance is open (authentication off, or a public demo), the assistant just works — there is nothing to configure.

If the instance requires authentication, give the connector an **API key in its `Authorization` header** wherever the assistant allows one. You set the header once when you add the connector, and Enterfind turns the key into a short session on its own side for each request. The key and the session never pass through the conversation: the assistant doesn't see them, can't repeat them, and doesn't ask you for anything. This is the recommended setup, and it is the only one that works with Claude, which declines to handle credentials typed into a chat.

The header can carry the key on its own (`efk_…`) or as `Bearer efk_…`. It works on the `/mcp` endpoint only, both through the remote proxy URL and directly against `http://localhost:8080/mcp` or a LAN address.

Where the assistant has no header setting, Enterfind falls back to logging in inside the conversation. The assistant asks you for your API key, logs in, and uses the resulting token for the rest of the session. An assistant that is told an instance needs sign-in, but has no key, is pointed at the instance's header setup instructions rather than asking you to paste a key: the Public AI Setup page when it connects through Remote Access, and the Local MCP Setup page when it connects directly.

A **READER-level** API key is the right choice for most assistant connections: the assistant can search and retrieve, but can't reconfigure your instance. See [Users and Access Control](users-and-access.md) for creating one. Some assistants can't edit a connector's header once it is saved, so a new key means removing and re-adding the connector. Create the key with a long expiry.

## Connecting Claude

Claude reaches Enterfind over MCP, by either route above.

- **claude.ai / Claude Desktop / mobile (cloud connector):** add a custom connector with your remote `/mcp` URL. Leave authentication at **No sign-in**. If the instance needs authentication, add a **Request header** named `authorization` with the value `Bearer efk_…`. Claude introspects the connector, and the Enterfind tools appear in your conversations. Requires [Remote Access](remote-access.md). Headers can't be edited after the connector is saved, and Request headers are a Claude beta that not every account shows yet.
- **Claude Code:** `claude mcp add --transport http enterfind <url>/mcp --header "Authorization: Bearer efk_…"`. The URL can be the remote one or your instance directly, such as `http://localhost:8080/mcp`.
- **Claude Desktop (local wrapper):** use the local MCP server wrapper pointed at your instance's `/graphql` URL, configured in Claude Desktop's MCP servers file. No Remote Access required. The wrapper talks to `/graphql`, which has no header option, so on an instance with authentication, prefer the cloud connector above.

## Connecting ChatGPT

ChatGPT connects as a **Custom GPT Action** over **OpenAPI** rather than MCP. Your instance publishes an OpenAPI description at:

```
https://<serverId>.remote-<stage>.enterfind.com/openapi.json
```

Create a GPT, and under **Actions** import that URL as the schema; ChatGPT reads it and exposes a single operation for calling Enterfind's GraphQL endpoint. Leave the action's authentication set to **None** — Enterfind's own auth is handled inside the request, not at that layer. Because ChatGPT runs in the cloud, this route requires [Remote Access](remote-access.md); you can open the `/openapi.json` URL in a browser first to confirm it's reachable.

If the GPT answers from memory instead of searching, tell it explicitly to use the Enterfind action ("Search Enterfind for…").

## Connecting Gemini

Gemini connects only through the **Gemini CLI** (the command-line tool), over MCP, using the same remote `/mcp` endpoint as Claude. The hosted Gemini apps don't consume MCP servers, so the CLI is the path.

The simplest setup is to ask the CLI, in plain language, to add an MCP server named `enterfind` at your `/mcp` URL — it writes the entry to its settings and connects without a restart. You can also edit `~/.gemini/settings.json` by hand, adding Enterfind under `mcpServers` with the endpoint as the `httpUrl`. If the instance needs authentication, add the key as a header:

```json
"mcpServers": {
  "enterfind": {
    "httpUrl": "https://<serverId>.remote-<stage>.enterfind.com/mcp",
    "headers": { "Authorization": "Bearer efk_…" }
  }
}
```

Run `/mcp` in the CLI to confirm it's connected.

## Other assistants

Assistants with no header setting can still use a private instance through the in-chat login described under [How authentication works with assistants](#how-authentication-works-with-assistants).

**Muse AI (Meta)** has been seen to do this successfully. Ask it to log in to Enterfind and give it the API key's nickname and value. It calls `loginWithApiKey` itself and uses the resulting token for the rest of the conversation, with no header setup. The key does pass through the conversation on this path, so use a READER key with a short expiry, and delete it in Settings → API keys when you are done.

## Checking that it actually works

A connected assistant can look fine and still be wrong. The two failure modes worth ruling out are that it answers from its own memory instead of searching at all, and that it searches but interprets your request loosely — so you get a confident answer that nothing in your corpus supports.

Two questions catch both, and they work on any instance because they use your own documents. Pick a two-word phrase you know appears, word for word, in the text of exactly one or two documents.

**First, ask for the phrase two ways.** Ask which documents contain the phrase *as an exact phrase*, then ask which contain both of those words *anywhere in the text, adjacent or not*. A properly connected assistant gives you a **smaller set for the exact phrase** — quoting is how Enterfind is told to require words to be adjacent and in order, and the assistant should be putting quotes around the phrase for the first question and leaving them off for the second. See [Quoting](search.md#quoting). If both questions return exactly the same documents, the assistant is probably not distinguishing them — or not really searching.

**Then ask for something that cannot work.** Ask it to find documents whose text contains a fragment from the *middle* of a word — `istribut` rather than `distribut`, say. Enterfind can match a fragment anywhere inside a file name or path, but document text is matched by whole words, so a mid-word fragment finds nothing. The right answer is for the assistant to tell you that plainly and offer the prefix instead. If it hands you a list of documents, it is inventing them.

**Ask it to show you the query it ran.** This is the single most useful habit. Every assistant connected over MCP or as a GPT Action is issuing a GraphQL query to your instance, and all of them can show it to you. A named document list plus the query that produced it is checkable; a summary with neither is not.

If you are running Enterfind from source and want to check a change you have just made, the same
two questions work against a local build — see
[Testing MCP Search Locally](testing-mcp-locally.md) for how to stand one up and verify the
answers against the database.

If the answers look wrong, work through it in this order: confirm the assistant lists the Enterfind tools at all, then that it is pointed at *your* instance rather than a demo, and, if your instance requires authentication, that the connector carries your API key in its `Authorization` header. A bad or expired key comes back as a tool error that names the reason.

---

*Assistant menus and labels change over time, so exact click paths may differ from what you see. Everything on the Enterfind side — the connection URLs, the single OpenAPI operation, and the authentication behavior — is stable. For the exact local configuration of Claude Desktop, Claude Code and the Gemini CLI, use the Local MCP Setup page served by your own instance.*

---

**See also:** [Remote Access](remote-access.md) · [AI Helper](ai-helper.md) · [Users and Access Control](users-and-access.md) · [The API](api.md)

[← Back to documentation index](../README.md)
