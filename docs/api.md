# The API

Everything Enterfind does is reachable through a single **GraphQL** endpoint. The web UI is a client of it; so are the MCP and OpenAPI layers that connect AI assistants. If you want to script around Enterfind — push documents in, pull search results out, automate scans, integrate with something else — this is the surface.

This page is an orientation, not a full schema reference. The instance serves an interactive **GraphiQL** explorer (at `/graphiql`) where you can browse the complete schema and run queries against your own data.

## The endpoint

```
POST http://<your-instance>/graphql
```

A single endpoint handles every query and mutation. Over [Remote Access](remote-access.md) the same endpoint is reachable at your remote URL, and the AI layers (`/mcp`, `/openapi.json`) are thin wrappers over it.

## Authentication

If the instance requires authentication, you first exchange an [API key](users-and-access.md) for a session token, then pass that token with subsequent operations.

**One important detail:** the token is passed as an **argument to the GraphQL operation**, not as an HTTP header. Every query and mutation that can require auth takes an optional `token` argument. This is why AI assistants can authenticate purely by inspecting and calling the schema — there's no separate HTTP auth layer to configure.

Exchange your API key for a token with `loginWithApiKey`:

```graphql
mutation {
  loginWithApiKey(input: { nickname: "my-key-name", keyValue: "efk_..." }) {
    token
    expiresAt
  }
}
```

Then pass the returned `token` as an argument to whatever you call next:

```graphql
query {
  search(token: "the-token-from-above", searchString: "invoice", howmany: 20) {
    totalHits
    items { name folder dataSourceName sizeBytes }
  }
}
```

On an instance with authentication off, the `token` argument can be omitted entirely.

## Common operations

A tour of the operations you'll reach for most. Browse GraphiQL for the full set and every field.

### Search

```graphql
query {
  search(
    token: "…",
    searchString: "quarterly report",
    skip: 0,
    howmany: 40,
    sortDirection: DESC
  ) {
    totalHits
    items { name folder dataSourceName dataSourceType sizeBytes mimeType }
  }
}
```

Add structured filters with `searchConditions`, scope with `folder` or `name`. A condition is `{ name: "<key>", operation: EQUAL, value: "…" }`; the operations are `LESS`, `GREATER`, `EQUAL`, `NOT_EQUAL`, `LIKE`, `NOT_LIKE`. See [Search](search.md) for the concepts.

### Discover what's searchable

- `standardFields` — the intrinsic field names every item has.
- `itemAttributeKeys` — all attribute keys in the corpus.
- `availableItemAttributeKeys(...)` — keys available given a search you've started, for building a filter against what's actually present.
- `getFacets(key: "…", …)` — the distribution of one attribute key across a result set (one key per call).

See [Item Attributes](item-attributes.md) for the concepts behind these.

### Item detail and content

- `itemAttributes(dataSourceName: …, dataSourceType: …, name: …)` — the key/value attributes of a specific item.
- The `Item` type carries `downloadLink`, `previewLink`, and `rawBase64Content` for retrieving the document itself, plus `faces` for per-face labels on images.

### Manage data sources

- `dataSources` / `dataSource(...)` — list or fetch sources.
- `createLocalFileSystemDataSource`, `createRemoteFileSystemDataSource`, `createS3BucketDataSource` — create each source type; matching `update…` mutations edit them.
- `testDataSource(...)` — verify connectivity and preview visible files/folders before scanning.
- `scanDataSource(...)` — trigger a scan; `dataSourceStats(...)` (and the `dataSourceStatsUpdates` subscription) report progress.
- `clearDataSourceData(...)` / `deleteDataSource(...)` — reset or remove a source.

### Manage treatments

- `availableTreatments` — the treatments this instance supports.
- `dataSourceTreatments(...)` — treatments configured on a source.
- `addDataSourceTreatment(...)`, `updateDataSourceTreatmentConfig(...)`, `removeDataSourceTreatment(...)` — configure them per source. Removing a treatment also removes the data it produced (see [Treatments](treatments.md)).

### Saved queries

- `savedQueries` / `savedQuery(...)` — list or fetch your saved queries.
- `createSavedQuery(...)` / `deleteSavedQuery(...)` — save a search or remove one. See [Saved Queries](saved-queries.md).

### Item management

- `deleteItem(...)` — remove a specific item.

External pipelines can also push documents into Enterfind through the API, so files produced elsewhere are treated and searched alongside filesystem-sourced content. This is what makes Enterfind an indexing engine you can build on, not just an indexer of file shares.

## A note on capabilities and level

What an authenticated caller may do follows its [level](users-and-access.md): a **READER** token can search and read; an **ADMIN** token can also create and modify sources, treatments, users, and settings. Give an integration the lowest level that does its job — a READER key for anything that only needs to search and retrieve.

## Explore it live

The fastest way to learn the schema is to open **`/graphiql`** on your instance: it lists every query, mutation, and type with inline documentation, and lets you run operations against your real data. Everything above is a starting point into that.

---

**See also:** [Search](search.md) · [Item Attributes](item-attributes.md) · [Saved Queries](saved-queries.md) · [Users and Access Control](users-and-access.md) · [Connecting AI Assistants](ai-access.md)

[← Back to documentation index](../README.md)
