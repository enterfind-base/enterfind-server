# Operating Your Instance

[Installation](installation.md) gets Enterfind running. This page is the other half: what to look at once it is, and what to do when something looks wrong.

Nothing here is required reading for a working instance. It is the page to have open when a scan seems stuck, when the disk fills up, before an upgrade, or when you want to know what to back up.

The examples assume Docker. If you used the [installer](installation.md#installer-no-docker), the same things live in different places; each section says where, and the installer's [guide](https://github.com/enterfind-base/enterfind-installer) covers its commands in full.

## Is it running?

The actuator health endpoint answers the basic question without the UI:

```bash
curl -s http://localhost:8080/actuator/health
```

On startup the server initializes its own schema — creating any missing tables and indexes and applying any pending migrations — before it begins serving normally. On a large corpus the first boot after an upgrade can take noticeably longer than usual if that upgrade added an index, because the index is built over existing rows. A slow first start after a version bump is expected; a slow start on every boot is not.

**Installer:** `enterfind status` answers this, and `enterfind doctor` runs a set of checks with a pass, warn or fail each. The health endpoint is not on the main port: the installer moves it to a loopback-only management port, recorded as `managementPort` in `~/.enterfind/state.json` (54330 unless that was taken), so the `curl` above becomes `curl -s http://127.0.0.1:<managementPort>/actuator/health`. With autostart on (the default), the installer also checks every five minutes and starts whatever has stopped, unless you stopped it with `enterfind stop`.

## Reading the logs

**The application logs to files, not to standard output.** `docker logs` shows you the Spring Boot banner and very little else, which is the single most misleading thing about a fresh install — it looks like the app produced nothing.

The logs live at `/app/logs/` inside the container:

- `enterfind-server.log` — the current log
- `enterfind-server.YYYY-MM-DD_HH.log` — rotated hourly

Every Compose setup in [Installation](installation.md#using-docker) bind-mounts that directory (`./logs:/app/logs`), so the usual route is simply to read them on the host:

```bash
tail -f ./logs/enterfind-server.log
grep -E 'ERROR|WARN' ./logs/enterfind-server.log
```

If you started the container without that bind-mount, reach them through the container instead:

```bash
docker exec <container> ls /app/logs/
docker exec <container> tail -f /app/logs/enterfind-server.log
```

**Installer:** the same files are in `~/.enterfind/logs/`, next to the installer's own `installer.log` (which also records what the five-minute check did). `enterfind logs` prints the end of the server's log and `enterfind logs -f` follows it; `enterfind logs postgres` and `enterfind logs installer` show the others. PostgreSQL's own log is in `~/.enterfind/data/postgres/log/`.

Scan and treatment logging is per item at INFO, so a busy instance produces a lot of it. Filtering to `ERROR|WARN` is usually the right first move: the conditions worth knowing about — failures, retries, and the startup resets below — are all logged at those levels.

## The two counts that mean "read the log"

Each data source shows two problem counts, and they work the same way: **zero is the normal state, and any non-zero value means the log holds a reason that the count itself does not.** Neither is an error dialog — the instance keeps working, with part of the corpus stale or unprocessed — so nothing brings them to your attention except looking.

**Folders with problems** counts folders that failed to list during the last scan while the rest of the scan succeeded — a permissions change, a disappearing mount point, an SMB share that dropped mid-scan. A folder that could not be listed keeps its existing contents indexed and is retried on the next scan; it is never mistaken for an empty folder whose contents should be deleted. So a non-zero count means part of the tree is stale, not that anything was lost.

```sql
SELECT ds.name AS datasource, COUNT(*) AS folders_with_problems
FROM folders f
JOIN data_sources ds ON f.datasource_id = ds.id
WHERE f.scan_failure_count > 0
GROUP BY ds.name;
```

**Failed treatments** appear as the `FAILED` entries in the per-treatment status breakdown: an item whose text extraction, OCR or detection did not complete. The item is indexed and findable by name and path; what is missing is that treatment's contribution to search. Causes are usually per-file — a corrupt PDF, an image OCR cannot read, a file that grew past a limit mid-scan — so a handful of failures on a large corpus is ordinary and a sudden jump is not.

```sql
SELECT ds.name AS datasource, dt.treatment, COUNT(*) AS failed
FROM item_treatments it
JOIN datasource_treatments dt ON it.datasource_treatment_id = dt.id
JOIN data_sources ds ON dt.datasource_id = ds.id
WHERE it.status = 'FAILED'
GROUP BY ds.name, dt.treatment
ORDER BY failed DESC;
```

The one difference between the two is what happens next without you. Both retry, but a folder retries on every scan indefinitely, while a failed treatment retries only up to the `TreatmentMaxFailureCount` setting (default 4) and is then left alone. A `FAILED` row whose `failure_count` has reached that ceiling will not be attempted again — it needs the underlying file fixed, or the retry budget raised:

```sql
SELECT i.name, i.path, dt.treatment, it.failure_count
FROM item_treatments it
JOIN items i ON it.item_id = i.id
JOIN datasource_treatments dt ON it.datasource_treatment_id = dt.id
WHERE it.status = 'FAILED'
ORDER BY it.failure_count DESC, i.name
LIMIT 20;
```

With a name from that list, the log is where the reason is — the counts and statuses deliberately do not carry it.

## Checking on scans

A data source is in one of three statuses: `IDLE`, `SCANNING`, or `PROCESSING`. That plus the last-scan timestamp is usually enough to tell what is happening:

```sql
SELECT id, name, status, date_last_scanned
FROM data_sources
ORDER BY date_last_scanned DESC NULLS FIRST;
```

**A `date_last_scanned` that stops advancing** is the third signal worth knowing, and it is the one for a whole data source rather than part of one. It means the scan root itself could not be listed — an unmounted volume, an SMB share that stopped answering, expired S3 credentials. When the root listing fails, the scan aborts rather than concluding, and deliberately does not stamp the timestamp. The stale timestamp *is* the alarm; there is no problem count for it, because nothing was successfully scanned to count against. The scheduler keeps retrying on its normal cadence, so the instance recovers by itself once the source is reachable again.

## After a crash: what fixes itself

If the server was killed mid-scan, rows are left claiming work that is no longer running. This is repaired on the next startup, not left for you:

- Data sources stuck in `SCANNING` are reset to `IDLE`.
- Item treatments stuck in `INPROGRESS` are reset to `FAILED`.

Both are logged at WARN with a count, so a restart that repaired something says so:

```
Reset 1 datasource(s) from SCANNING to IDLE status on startup
Reset 47 item treatment(s) from INPROGRESS to FAILED status on startup
```

The practical consequence: **a data source that shows `SCANNING` forever, with nothing progressing, clears on a restart** (`enterfind restart` with the installer). You do not need to edit the database to unstick it.

The treatments reset to `FAILED` are not lost work — `FAILED` is a pending status, so they are picked up and retried as above. Treatment handlers are idempotent by design, so a re-run is always safe. This is also why a restart can make the failed count jump: it counts genuine failures and interrupted work alike.

## Inspecting the database

The database name, user and password are the ones you set in your environment, not fixed values — check your `.env` rather than assuming:

```bash
docker exec -it <postgres-container> psql -U "$DB_USERNAME" -d "$DB_NAME"

# if you are not sure which database this deployment uses
docker exec <postgres-container> psql -U "$DB_USERNAME" -l
```

**Installer:** its PostgreSQL listens on `127.0.0.1` only, on the port recorded as `postgresPort` in `~/.enterfind/state.json` (54329 unless that was taken). The database and user are both `enterfind`; the password is `appPassword` in `~/.enterfind/config/secrets.json`. The installer bundles no `psql`, so use any PostgreSQL client on the same computer:

```bash
psql -h 127.0.0.1 -p <postgresPort> -U enterfind -d enterfind
```

**How much is indexed:**

```sql
SELECT 'data_sources' AS table, COUNT(*) FROM data_sources
UNION ALL SELECT 'items', COUNT(*) FROM items
UNION ALL SELECT 'folders', COUNT(*) FROM folders
UNION ALL SELECT 'item_treatments', COUNT(*) FROM item_treatments
UNION ALL SELECT 'item_treatment_results', COUNT(*) FROM item_treatment_results
UNION ALL SELECT 'item_treatment_results_content', COUNT(*) FROM item_treatment_results_content;
```

**Where the treatment backlog is**, broken down by data source and treatment. `DONE` is the terminal success status:

```sql
SELECT ds.name AS datasource, dt.treatment, it.status, COUNT(*)
FROM item_treatments it
JOIN datasource_treatments dt ON it.datasource_treatment_id = dt.id
JOIN data_sources ds ON dt.datasource_id = ds.id
GROUP BY ds.name, dt.treatment, it.status
ORDER BY ds.name, dt.treatment, it.status;
```

**Slow queries**, if you are chasing a performance problem. The server tries to enable `pg_stat_statements` at startup, but the extension also needs to be listed in `shared_preload_libraries` in `postgresql.conf`; when it is not, the server logs a warning at startup and this query will not work:

```sql
SELECT substring(query, 1, 100) AS short_query, calls, mean_exec_time, max_exec_time
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 10;
```

## Tuning throughput

Treatment concurrency is configurable from the UI's settings, stored in the database rather than in environment variables. The defaults:

| Setting | Default | What it limits |
|---|---|---|
| `ConcurrentTreatmentProcessors` | CPU count − 1 | Treatments processed at once, across all types |
| `ConcurrentOcrHandlers` | 2 | Simultaneous OCR runs |
| `ConcurrentObjectDetectionHandlers` | 2 | Simultaneous object detection runs |
| `ConcurrentFaceDetectionHandlers` | 2 | Simultaneous face detection runs |
| `ConcurrentFaceRecognitionHandlers` | 2 | Simultaneous face recognition runs |
| `TreatmentMaxFailureCount` | 4 | Attempts before a failed treatment is abandoned |

The per-type limits exist because OCR and the ONNX-based treatments are far heavier than text extraction; raising the global processor count without capping those can saturate a small box. Scanning itself runs on a pool sized at CPU count − 2 and is not configurable.

## Disk growth

Indexed content, not the original files, is what grows. Originals stay where they are in the data source; Enterfind stores extracted text and metadata.

```sql
SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size;

SELECT tablename,
       pg_size_pretty(pg_total_relation_size('public.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size('public.'||tablename) DESC
LIMIT 15;
```

The usual order of size, largest first:

- **`item_treatment_results_content`** — extracted document text, from the Content and OCR treatments, plus a GIN full-text index over it.
- **`item_treatment_results`** — metadata key/value facts, plus a trigram GIN index on values for substring search. Large corpora reach millions of rows here.
- **`item_previews`** — generated preview images, stored as binary.
- **`items`** — one row per file, with trigram indexes on name and path.

Index overhead is a real fraction of the total, not a rounding error; the trigram and full-text indexes are what make search fast and they are not small. `pg_total_relation_size` above includes them, so the numbers you see are the honest ones.

One thing to know when interpreting search behaviour rather than size: only the first portion of a very large document's extracted text is covered by the full-text index. The full text is stored; see [Search](search.md) for what that means for matching.

## Backup and restore

A logical dump of the database captures everything Enterfind knows:

```bash
docker exec <postgres-container> pg_dump -U "$DB_USERNAME" "$DB_NAME" | gzip > enterfind-$(date +%F).sql.gz
```

Restore into an empty database of the same name, then start the server; it will reconcile its schema on boot.

**Installer:** it bundles no `pg_dump`. Either dump with a PostgreSQL client of the same or a newer major version than the installer's PostgreSQL (`enterfind status` shows it), using the connection details from *Inspecting the database* above:

```bash
pg_dump -h 127.0.0.1 -p <postgresPort> -U enterfind enterfind | gzip > enterfind-$(date +%F).sql.gz
```

or stop Enterfind with `enterfind stop`, copy `~/.enterfind/data/` and `~/.enterfind/config/` as they are, and `enterfind start` again. A copy taken while PostgreSQL runs is not a backup.

**Back up `ENTERFIND_ENCRYPTION_KEY` separately, and treat losing it as data loss.** Data-source credentials (SMB passwords, S3 keys) are encrypted with it before being stored, so a dump restored without the original key leaves those configurations unreadable and the affected data sources have to be recreated by hand. The dump alone is not a complete backup. With the installer the key is `encryptionKey` in `~/.enterfind/config/secrets.json`, generated at install: back up that file.

Two related notes:

- If you set the encryption key for the first time on an instance that already has data sources, their stored configurations are migrated from plaintext to encrypted on the next boot. Setting it later is supported; leaving it unset means those credentials sit in the database as plaintext.
- What is *not* in the dump, and does not need to be: the indexed files themselves, and the ONNX models for object and face detection, which are cached on disk and re-fetched on demand.

## Upgrading and rolling back

Pull a newer image and restart. If you run `latest`, [Watchtower](installation.md#automatic-updates-watchtower) does this for you; if you pin version tags, bump the tag deliberately.

**Installer:** `enterfind update --check` lists what is available and `enterfind update --yes` installs it; the guided mode checks each time you run it. Updates stay within the installed major version. The installer keeps the previous version on disk, and switches Java, PostgreSQL or Tesseract back by itself if the server does not come up after updating them — but never the server: after a server update the database may already be migrated, which is the case below.

**The schema is applied forward only.** On each boot the server brings the database up to the shape the running build expects, and there is no reverse path: some of those migrations drop columns or tables that a newer shape has replaced. So rolling the image back to an older tag does *not* roll the schema back, and downgrading across a version that migrated the schema leaves an old jar in front of a database it does not expect.

In practice:

- **Take a dump before upgrading.** It is the only rollback that covers the database, and it costs a minute.
- **Test upgrades on a copy** if the instance matters and the corpus is large enough that re-indexing would be painful.
- Expect the first boot on a new version to take longer than usual, and watch the log rather than the clock — schema work is logged as it happens.

---

**See also:** [Installation](installation.md) · [Data Sources](datasources.md) · [Treatments](treatments.md) · [Users and Access Control](users-and-access.md) · [Remote Access](remote-access.md)

[← Back to documentation index](../README.md)
