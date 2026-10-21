# Remote Access

By default an Enterfind instance is reachable only where it runs — on the machine itself or across your local network. Remote Access makes it reachable from the public internet through Enterfind's own tunnel, without you configuring port forwarding, a reverse proxy, or a firewall rule.

## Why you'd want it

The main reason is **cloud AI assistants**. An assistant that runs in the cloud — claude.ai, a Claude custom connector, ChatGPT — can only reach your documents if your instance has a public address. Remote Access provides that address. (An assistant running on your own machine can instead talk to the instance directly; see the local MCP option in [Connecting AI Assistants](ai-access.md).)

It's also simply a way to reach your own instance's web UI from outside your network.

## How it works

When you enable Remote Access, your instance opens an **outbound** connection to the Enterfind tunnel and takes up a stable **Server ID**. Your instance then has a public URL of the form:

```
https://<serverId>.remote-<stage>.enterfind.com/
```

with the AI endpoints hanging off it — `/mcp` for MCP assistants and `/openapi.json` for ChatGPT. Your Server ID and full remote URL are shown on the Settings page under Remote Access.

Because the connection is outbound, there's nothing to open on your router or firewall — the instance reaches out to the tunnel, and the tunnel relays traffic back.

## Getting an invite code

The tunnel needs to know your instance. Invite codes are issued on request: email [info@enterfind.com](mailto:info@enterfind.com). If you were sent an **invite code** (it starts `efi_`), enter it in the Remote Access panel on the Settings page and click **Use invite code**. That switches Remote Access on; the tunnel assigns your instance a Server ID and a credential, which are saved automatically, and the Server ID and remote URL appear in the panel a moment later.

An invite code works **once**. Once it has been used you don't need it again, and it can't be used to connect a second instance. If it isn't accepted — mistyped, withdrawn, or already used — the panel says so, and you can enter it again or ask for a new one at the same address.

Switching Remote Access off and on keeps your Server ID and URL. Using a **new** invite code on an instance that already has a Server ID moves it to a new Server ID and URL, after asking you to confirm — AI assistants configured with the old URL then need the new one. If the new code isn't accepted, the instance keeps its current Server ID.

Without an invite code, switching Remote Access on makes your instance try to register itself directly. Whether the tunnel accepts that depends on how it is currently run: **at present it does not**, and the Settings page says so.

## The tunnel is a hosted service

The remote proxy that makes this work — the relay your instance connects out to and that serves its public URL — is **operated by Enterfind's creators as a hosted service**, not something that runs on your hardware. Your documents still live only on your instance; the proxy relays traffic to and from it. A few practical implications:

- **It's subject to availability.** As a hosted service it can have maintenance windows or outages. When the proxy is unreachable, remote access is interrupted; your instance and its local/LAN access are unaffected and keep working normally.
- **It may be throttled or capped.** Usage through the tunnel can be rate-limited or subject to limits. Enterfind surfaces your tunnel **usage** so you can see how much traffic you're carrying.
- **Local access never depends on it.** The tunnel only affects reaching your instance from *outside*. Everything on your own machine or network works whether or not the proxy is up.

If you'd rather not depend on a hosted relay at all, you can keep Remote Access off and reach your instance another way — over your LAN, or through your own reverse proxy or tunnel (see [Compose: behind your own proxy](installation.md#compose-behind-your-own-proxy) in Installation) — and connect a local AI assistant directly per [Connecting AI Assistants](ai-access.md).

## Enabling and monitoring it

From the Settings page you can:

- **Enable or disable** Remote Access.
- Enter an **invite code** and click **Use invite code** to connect the instance, or to move it to a new Server ID.
- See your **Server ID** and the **remote URL** to hand to an assistant.
- See **connection status** — whether the tunnel is currently connected, when it last connected, and any status message explaining a failure.

The status updates live, so you can confirm the tunnel is up before pointing an assistant at it.

## Secure it before you expose it

Enabling Remote Access means your instance is reachable from the internet. Before you do, make sure authentication is on unless the instance is meant to be public — otherwise anyone with the URL has full access. See [Users and Access Control](users-and-access.md), and prefer a **READER-level** API key when connecting an AI assistant so a leaked key can't reconfigure the instance.

## Turning it off

Disabling Remote Access closes the tunnel and the public URL stops working immediately. Local and LAN access are unaffected — the instance keeps serving where it always did.

---

**See also:** [Connecting AI Assistants](ai-access.md) · [Users and Access Control](users-and-access.md) · [Installation](installation.md)

[← Back to documentation index](../README.md)
