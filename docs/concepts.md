# Concepts

The vocabulary Enterfind uses, in one place. Other pages link back here rather than redefining terms.

## Data source

A place documents live that Enterfind can read. Enterfind supports three kinds: a **local filesystem** path, a **remote filesystem** network share (SMB/CIFS), and an **S3-compatible bucket**. A data source has a name, an optional scan schedule, and an optional skip pattern. See [Data Sources](datasources.md).

## Scan

The act of walking a data source to discover its files. Each scan reconciles what's on the source with what Enterfind already knows: new files become new items, changed files are marked updated, and treatments run against whatever needs them. A source is either **IDLE** or **SCANNING** at any moment. Scans run on demand or on the source's cron schedule.

## Item

A single document Enterfind has discovered in a data source. An item carries its name, folder, size, MIME type, timestamps, and — once treatments have run — its extracted text and attributes. Items are what search returns.

## Folder

The path structure within a data source. Items belong to folders, and search can be scoped to a folder.

## Treatment

A unit of processing applied to items in a data source. Enterfind ships six: **Name and Path**, **Content**, **OCR**, **Object Detection**, **Face Detection**, and **Face Recognition**. Most are enabled per data source, so different sources can be processed differently; **Name and Path is always enabled** everywhere. See [Treatments](treatments.md).

## Treatment status

Each item tracks where it stands for the treatments configured on its source: **NEW**, **UPDATED**, **INPROGRESS**, **DONE**, or **FAILED**. A source's stats show how many items sit in each state — this is how you tell whether processing has caught up.

## Item attribute

A key/value fact about an item. **Standard fields** (name, folder, size, type, timestamps, source) exist for every item; **treatment-produced attributes** (extracted text and metadata, detected object labels, face labels, and so on) are added by whichever treatments ran. Attributes are searchable and can be used as facets. See [Item Attributes](item-attributes.md).

## Facet

A count of how many items share each value of a given attribute key — the mechanism behind filtered, drill-down search. Ask for the facets of a key and you get each distinct value and how many items carry it.

## Search condition

A single structured filter on a search: an attribute key, an operation (equal, not-equal, greater, less, like, not-like), and a value. Conditions combine with a free-text search string and a folder scope. See [Search](search.md).

## Saved query

A named, stored search — its search string, conditions, and sort direction — that a user can recall and re-run later. Personal to the user who created it. See [Saved Queries](saved-queries.md).

## User level

Enterfind has two access levels: **READER** (can search and read) and **ADMIN** (can also configure sources, treatments, users, and settings). Authentication is optional per instance; when it's off, everyone is effectively an admin. See [Users and Access Control](users-and-access.md).

## API key

A credential that authenticates a non-interactive caller (a script, or an AI assistant) at READER or ADMIN level. Exchanged for a session token via a GraphQL mutation. See [The API](api.md).

## Remote access

An outbound tunnel that makes a local instance reachable from the public internet without opening a port, via a hosted relay operated by Enterfind's creators. Required for cloud AI assistants to reach your documents. See [Remote Access](remote-access.md).

## MCP and OpenAPI

The two protocols AI assistants use to reach Enterfind. **MCP** (Model Context Protocol) is what Claude and the Gemini CLI speak; **OpenAPI** is what ChatGPT Custom GPTs speak. Both are thin wrappers over the same GraphQL API. See [Connecting AI Assistants](ai-access.md).

---

**See also:** [Getting Started](getting-started.md) · [Treatments](treatments.md) · [Item Attributes](item-attributes.md) · [Search](search.md)

[← Back to documentation index](../README.md)
