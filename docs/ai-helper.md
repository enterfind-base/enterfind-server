# AI Helper

Separate from connecting an external assistant to your documents, Enterfind has a small AI **helper** built into the product itself. It doesn't chat about your corpus — its job is to make a few fiddly configuration tasks easier by translating between plain language and the exact syntax Enterfind needs.

This is distinct from [Connecting AI Assistants](ai-access.md): that page is about letting Claude or ChatGPT search your documents from the outside. The AI helper here is an in-product convenience that turns your words into settings.

## What it helps with

- **Cron schedules.** Describe a scan schedule in plain language ("every night at 2am", "every Monday morning") and the helper produces the cron expression a data source needs. It also works in reverse: hand it an existing cron expression and it explains, in plain language, when that schedule fires. See [Data Sources](datasources.md) for where schedules are set.
- **Skip patterns.** Describe the files you want a scan to ignore and the helper produces the skip pattern; give it an existing pattern and it explains what that pattern matches.
- **Natural-language search.** Describe what you're looking for and the helper interprets it into a structured search — search conditions and a search string — against the attribute keys your corpus actually has. It reports how confident it is and explains what it built, so you can adjust. See [Search](search.md).

## Configuring a provider

The helper runs on an AI provider you configure in the instance's AI settings. Enterfind supports four options:

- **None** — no provider; the helper is off. This is the default, and Enterfind runs completely normally without a provider (see *When you don't need it* below).
- **Ollama** — a local model server you run yourself. Requests stay on your own machine or network; nothing goes to a third party. The right choice when privacy is the priority.
- **OpenAI** — OpenAI's hosted models. Requires an OpenAI API key.
- **Anthropic** — Anthropic's (Claude) hosted models. Requires an Anthropic API key.

### What each provider needs

The AI settings hold the provider selection plus the settings that provider requires:

- **Ollama** — the address of your Ollama server (for example a local `http://…:11434`) and the model to use. Because you host it, there's no API key; the cost is that you run and keep the model server available.
- **OpenAI** — your API key, and the model to use. Usage is billed by OpenAI against that key.
- **Anthropic** — your API key, and the model to use. Usage is billed by Anthropic against that key.

The exact setting fields appear in the AI section of Settings once you pick a provider. After entering them, use the built-in **connection test** to confirm Enterfind can actually reach the provider with the credentials given, before you rely on it for a real task. A provider that fails the test won't work for the helper features until the settings are corrected.

### Switching providers

The provider is an instance-level choice, changeable at any time in AI settings. Switching from, say, Ollama to Anthropic just changes which backend the helper's next request goes to — it doesn't affect your indexed documents or anything else. You can move between local and hosted providers freely as your priorities (privacy, cost, quality) change.

## Privacy: what the helper sees

The helper only sends what a given task requires — a schedule description, a skip-pattern description, or a search phrase together with your corpus's *attribute key names* — to the configured provider. It does **not** send your document contents. With a hosted provider (OpenAI or Anthropic) those small task inputs go to that provider under your API key and their terms; with **Ollama** they never leave your own infrastructure at all. If you want the helper's convenience with none of the data leaving your machine, Ollama is the way to get it.

## Relationship to connecting external AI assistants

Don't confuse this provider setting with [Connecting AI Assistants](ai-access.md):

- **This page (AI helper):** an AI backend Enterfind calls, on your behalf, to turn plain language into cron expressions, skip patterns, and structured searches. Configured in AI settings.
- **AI assistants:** external assistants (Claude, ChatGPT, Gemini) that reach *into* Enterfind over MCP or OpenAPI to search your documents. Configured on the assistant's side.

They're independent. You can run either, both, or neither.

## When you don't need it

Everything the helper does can be done by hand: you can write a cron expression, a skip pattern, or a structured search yourself. The helper is there to save you the syntax lookup, not to gate any capability. If you leave the provider set to None, Enterfind works exactly the same — you just enter these values directly.

---

**See also:** [Connecting AI Assistants](ai-access.md) · [Data Sources](datasources.md) · [Search](search.md) · [Users and Access Control](users-and-access.md)

[← Back to documentation index](../README.md)
