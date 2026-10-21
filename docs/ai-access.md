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

You configure the assistant to launch the wrapper against your instance's GraphQL URL — for example `http://localhost:8080/graphql` or a LAN address like `http://192.168.1.100:8080/graphql`. For Claude Desktop this is an entry in its MCP servers configuration pointing `node` at the wrapper script and your GraphQL URL. The instance's own MCP setup page walks through the exact configuration file location per platform and includes a one-line connection test.

This route is for **local or trusted-network** use — it's designed for a machine that can already reach the instance directly, and it doesn't require Remote Access. If the assistant is in the cloud, use the remote proxy URL instead.

### Which one?

- Assistant runs in the cloud (claude.ai, ChatGPT) → **remote proxy URL** (requires Remote Access).
- Assistant runs on your machine and can reach the instance directly (Claude Desktop on the same box or LAN) → **local MCP server** (no Remote Access needed).

Both expose the same Enterfind capabilities; they differ only in the path to your instance.

## How authentication works with assistants

Enterfind's authentication lives *inside* the GraphQL protocol, not as a separate HTTP login. In practice:

- The assistant can inspect the instance to discover whether it requires credentials.
- If the instance is open (authentication off, or a public demo), the assistant just works — nothing to configure.
- If the instance requires authentication, the assistant asks you for your **API key** in the conversation the first time it needs access, logs in, and uses the resulting token for the rest of the session.

There's no key to paste into the assistant's own settings — it's handled in-band. A **READER-level** API key is the right choice for most assistant connections: the assistant can search and retrieve, but can't reconfigure your instance. See [Users and Access Control](users-and-access.md) for creating one.

## Connecting Claude

Claude reaches Enterfind over MCP, by either route above.

- **claude.ai / custom connector (cloud):** add a custom connector using your remote `/mcp` URL. Claude introspects it and the Enterfind tools appear in your conversations. Requires [Remote Access](remote-access.md).
- **Claude Desktop (local):** use the local MCP server wrapper pointed at your instance's `/graphql` URL, configured in Claude Desktop's MCP servers file. No Remote Access required.

If the instance needs authentication, Claude asks for your API key when it first needs access.

## Connecting ChatGPT

ChatGPT connects as a **Custom GPT Action** over **OpenAPI** rather than MCP. Your instance publishes an OpenAPI description at:

```
https://<serverId>.remote-<stage>.enterfind.com/openapi.json
```

Create a GPT, and under **Actions** import that URL as the schema; ChatGPT reads it and exposes a single operation for calling Enterfind's GraphQL endpoint. Leave the action's authentication set to **None** — Enterfind's own auth is handled inside the request, not at that layer. Because ChatGPT runs in the cloud, this route requires [Remote Access](remote-access.md); you can open the `/openapi.json` URL in a browser first to confirm it's reachable.

If the GPT answers from memory instead of searching, tell it explicitly to use the Enterfind action ("Search Enterfind for…").

## Connecting Gemini

Gemini connects only through the **Gemini CLI** (the command-line tool), over MCP, using the same remote `/mcp` endpoint as Claude. The hosted Gemini apps don't consume MCP servers, so the CLI is the path.

The simplest setup is to ask the CLI, in plain language, to add an MCP server named `enterfind` at your `/mcp` URL — it writes the entry to its settings and connects without a restart. You can also edit its `settings.json` by hand, adding Enterfind under `mcpServers` with the remote endpoint as the `httpUrl`. Run `/mcp` in the CLI to confirm it's connected. As with the others, if the instance needs authentication the CLI asks for your API key when it first needs access.

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

If the answers look wrong, work through it in this order: confirm the assistant lists the Enterfind tools at all, then that it is pointed at *your* instance rather than a demo, and, if your instance requires authentication, that it has logged in with your API key.

---

*Assistant menus and labels change over time, so exact click paths may differ from what you see. Everything on the Enterfind side — the connection URLs, the single OpenAPI operation, and the in-band authentication behavior — is stable. For the exact per-platform local-wrapper configuration, use the MCP setup page served by your own instance.*

---

**See also:** [Remote Access](remote-access.md) · [AI Helper](ai-helper.md) · [Users and Access Control](users-and-access.md) · [The API](api.md)

[← Back to documentation index](../README.md)
