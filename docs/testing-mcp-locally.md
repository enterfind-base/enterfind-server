# Testing MCP Search Against a Local Build

This is the loop for checking that an AI assistant can actually search a **local development
copy** of Enterfind — built from source in the devcontainer, not a deployed instance. It is
worth running whenever you change search semantics, the GraphQL schema, or anything the MCP
tools expose, because those changes are invisible to the ordinary test suite: the suite proves
the DAO behaves, not that an assistant reading the schema can work out how to drive it.

The idea is to point a **fresh assistant with no prior knowledge of Enterfind** at your local
`/mcp` endpoint, ask it questions whose answers you can check against the database, and then
confirm from the server log that it really issued the queries it claims. For what to ask a
*connected* assistant against your real corpus, see
[Checking that it actually works](ai-access.md#checking-that-it-actually-works) — the questions
are the same; what follows is how to stand the whole thing up locally.

## 1. Populate the local database

Any corpus works, but the scanner integration tests already build one with known contents,
which is what makes the answers checkable:

```bash
mvn test -Dtest=SearchDaoTest
```

That leaves roughly a dozen items in `enterfind_test` — text files at several folder depths,
two PDFs with extracted text, and some images with EXIF metadata. The test wipes the database
on the way *in*, not on the way out, so the data is still there when it finishes. It deletes
its temporary source files, so the rows point at paths that no longer exist; that is fine for
searching, but downloads will not work.

## 2. Start the server

```bash
mvn spring-boot:run > server.log 2>&1 &
```

Redirect it to a file — step 5 reads that log to confirm what the assistant actually asked for,
and it is the only record of it. The `alpha` profile is the default and listens on 8080.

Wait for it to come up rather than guessing — the first request after launch is refused for a
while:

```bash
until curl -s -o /dev/null http://localhost:8080/actuator/health; do sleep 1; done
curl -s http://localhost:8080/actuator/health
```

Confirm the MCP endpoint answers JSON-RPC, and check whether the instance wants credentials:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -s -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ isAuthenticationRequired }"}'
```

A local alpha instance normally reports `false`, so the assistant needs no API key. If it
reports `true`, the assistant will ask for one in conversation — see
[Connecting AI Assistants](ai-access.md).

## 3. Point an MCP client at it

Any MCP client will do. With the Claude Code CLI, write a config file naming just this server:

```json
{"mcpServers":{"enterfind":{"type":"http","url":"http://localhost:8080/mcp"}}}
```

## 4. Drive it with an assistant that knows nothing

This is the part that makes the exercise meaningful, and the part that is easy to get wrong.
An assistant that has been working in the repository already knows how Enterfind search
behaves and will answer from that, not from the schema — which is precisely the knowledge a
real user's assistant does not have. Three flags keep it honest:

```bash
mkdir -p /tmp/blind && cd /tmp/blind   # not the project directory: no CLAUDE.md to read

claude -p "You have access to an MCP server for a document search system you have
never seen before. Learn what it can do from its own tools only.
<your question here>" \
  --mcp-config /path/to/mcp.json --strict-mcp-config \
  --allowedTools mcp__enterfind__introspect_graphql_schema mcp__enterfind__execute_graphql_query \
  --disallowedTools Bash Read Grep Glob Edit Write WebFetch WebSearch Task
```

- Run it from **outside the project directory**, or it loads `CLAUDE.md` and starts the test
  already knowing the answers.
- `--strict-mcp-config` limits it to the server you named. Without it, a client configured with
  a hosted Enterfind demo will happily query *that* instead, and the run tells you nothing about
  your build.
- `--disallowedTools` stops it reading the source instead of the schema.

Ask questions whose answers differ in a way only the schema explains. Two that work well on the
`SearchDaoTest` corpus:

> Which documents contain 'Distributed systems' as a contiguous phrase in their extracted text,
> and which contain both words anywhere, adjacent or not? Show the GraphQL you executed.

> Find every document whose extracted text contains the fragment 'istribut'. If that is not
> possible, say so, explain why, and show me the closest thing that is.

The first should return fewer documents for the quoted phrase than for the loose one. The second
should return nothing, with an explanation that document text matches whole words and a working
prefix search offered instead.

## 5. Verify, rather than believing the summary

An assistant can describe a query it never ran. Check both ends.

The server logs every MCP call and every GraphQL query it executes:

```bash
grep 'Received MCP request' server.log | wc -l
grep 'Executing GraphQL query' server.log | tail
```

And the database is the ground truth for the answers themselves:

```bash
PGPASSWORD=enterfind_test_password psql -U enterfind_test -d enterfind_test -h localhost -c "
SELECT i.name FROM items i
  JOIN item_treatments it ON it.item_id = i.id
  JOIN item_treatment_results_content c ON c.item_treatment_id = it.id
 WHERE c.key = 'content'
   AND c.value_tsv @@ phraseto_tsquery('simple', 'Distributed systems');"
```

Swap `phraseto_tsquery` for `plainto_tsquery` to get the loose form — those two are exactly what
a quoted and an unquoted term compile to, so they are the answers the assistant should have
produced.

## 6. Stop the server

```bash
pkill -f 'spring-boot:run'
curl -s -o /dev/null -w '%{http_code}\n' --max-time 3 http://localhost:8080/actuator/health
```

`000` means the port is closed. Do not check with `pgrep -f spring-boot` — that pattern matches
the `pgrep` command's own arguments and always reports a match, which looks like a server that
will not die.

---

**See also:** [Connecting AI Assistants](ai-access.md) · [Search](search.md) · [The API](api.md)

[← Back to documentation index](../README.md)
