# Enterfind Server Development Guide

## Project Overview

Enterfind is a Spring Boot 4.1.0 GraphQL server that indexes and searches files from various data sources (local filesystem, SMB shares, S3 buckets — the `DataSourceType` enum: LOCAL_FILESYSTEM, REMOTE_FILESYSTEM, S3_BUCKET). It uses PostgreSQL for storage, implements parallel scanning with ForkJoinPool, and provides an extensible treatment system for content extraction, OCR, object detection and face detection/recognition.

**Technology Stack:**
- Java 25 (Temurin/Adoptium)
- Spring Boot 4.1.0 with GraphQL
- PostgreSQL (with pg_trgm extension for fuzzy search)
- Maven build system
- JUnit 5 + Spring Test for testing
- Devcontainer for local development; Docker / Docker Compose for deployment

This guide covers working *in the repository*. For running and maintaining a deployed instance — logs, scan health, backups, upgrades — see [`docs/operations.md`](../../docs/operations.md), and treat that page as the place such knowledge belongs rather than adding it here.

## Local Development (Alpha Profile)

- **Environment:** Devcontainer (Ubuntu 24.04)
- **Profile:** `alpha` (default)
- **Database:** PostgreSQL running as a service in the devcontainer
- **Samba and MinIO:** running in the devcontainer for SMB and S3 testing
- **No Docker Compose:** all services run directly in the devcontainer

### Local endpoints

- GraphQL API: `http://localhost:8080/graphql`
- GraphiQL: `http://localhost:8080/graphiql`
- Health: `http://localhost:8080/actuator/health`

## Database Access

### Local Development Database

**Connection Details:**
- Host: `localhost`
- Port: `5432`
- Database: `enterfind_test`
- Username: `enterfind_test`
- Password: `enterfind_test_password`

These are devcontainer fixtures, created by `.devcontainer/init-test-db.sql` on container creation. They are not secrets.

**Connect via psql:**
```bash
# Direct connection (inside devcontainer)
psql -U enterfind_test -d enterfind_test

# Using environment variable for password
PGPASSWORD=enterfind_test_password psql -U enterfind_test -d enterfind_test -h localhost

# Or use connection string
psql postgresql://enterfind_test:enterfind_test_password@localhost:5432/enterfind_test

# As postgres superuser
sudo -u postgres psql
```

**Service Management (inside devcontainer):**
```bash
# Check PostgreSQL status
sudo service postgresql status

# Start/stop/restart PostgreSQL
sudo service postgresql start
sudo service postgresql stop
sudo service postgresql restart

# Check Samba status (see Test Data Sources — plain `pgrep -f smbd` gives false positives)
pgrep -c '^smbd$'

# Restart Samba
/usr/local/bin/start-samba.sh
```

### Deployed databases

A deployed instance's database name, user and password come from its `.env` — there is no fixed `enterfind` database to assume. Nothing in this repository depends on those values; inspecting a running deployment is covered in [`docs/operations.md`](../../docs/operations.md).

## Environment Configuration

### Configuration Files
- `application.properties`: Base Spring Boot configuration
- `application-alpha.properties`: Local development profile (default)
- `application-beta.properties`: Deployment profile
- `.env`: Docker environment variables for the Traefik+AWS reference deployment (not in git, use `.env.traefik-aws.template`)
- `.devcontainer/`: Devcontainer configuration for local development
  - `devcontainer.json`: VS Code devcontainer settings
  - `Dockerfile`: Devcontainer image definition
  - `init-test-db.sql`: PostgreSQL test database initialization
  - `smb.conf`: Samba configuration for SMB testing
  - `start-samba.sh`: Samba startup script

## Running the Application

**Prerequisites:**
The devcontainer automatically starts PostgreSQL on creation. Samba and MinIO must be started by hand (see Test Data Sources). Verify services are running:
```bash
# Check PostgreSQL
sudo service postgresql status

# Check Samba and MinIO
pgrep -c '^smbd$'
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9000/minio/health/live
```

**Run the application:**
```bash
# Using Maven (alpha profile is default)
mvn spring-boot:run

# With explicit profile
mvn spring-boot:run -Dspring-boot.run.profiles=alpha

# Run tests (uses enterfind_test database)
mvn test
```

**Devcontainer Details:**
- Based on Ubuntu 24.04
- Java 25 (Temurin/Adoptium)
- Maven
- PostgreSQL (running as service)
- Samba (for SMB testing) — start with `/usr/local/bin/start-samba.sh`
- MinIO (for S3 testing) — start with `/usr/local/bin/start-minio.sh`
- Tesseract OCR
- Claude Code CLI
- Port 8080 forwarded for Spring Boot app

## Architecture Components

### Core Layers

1. **Controllers** (`src/main/java/enterfind/server/controller/`)
   - `GraphQLController.java`: Main GraphQL API endpoint
   - `DownloadController.java`: File download endpoint

2. **DAO Layer** (`src/main/java/enterfind/server/dao/`)
   - `ItemDao.java`: CRUD for items and folders
   - `DataSourceDao.java`: Data source management
   - `TreatmentDao.java`: Treatment configuration & item treatment operations
   - `UserSettingsDao.java`: User settings persistence
   - `DBAccess.java`: Low-level JDBC operations wrapper
   - `Schema.java`: Database schema initialization and migrations

3. **Scanner** (`src/main/java/enterfind/server/scan/`)
   - `Scanner.java`: Orchestrates recursive scanning using ForkJoinPool
   - `DataProviderInterface.java`: Abstraction for data sources
   - `LocalFilesDataProvider.java`: Local filesystem implementation
   - `RemoteFileSystemDataProvider.java`: SMB/CIFS implementation
   - `S3DataProvider.java`: S3-compatible object storage implementation
   - `DatabaseDataProvider.java`: Reads current DB state for comparison
   - `Treatments.java`: the treatment enum (note: in `enterfind.server.scan`, not `model`)

4. **Treatment System** (`src/main/java/enterfind/server/scan/treatment/`)
   - `TreatmentProcessor.java`: Manages treatment execution lifecycle
   - `TreatmentHandler`: Interface for treatment implementations
   - `ContentTreatmentHandler.java`: Apache Tika content extraction
   - `OcrTreatmentHandler.java`: Tesseract OCR for images and PDFs
   - `ObjectDetectionTreatmentHandler.java`: YOLOX object detection
   - `NameAndPathTreatmentHandler.java`: Index file names and paths
   - `FaceDetectionTreatmentHandler.java`: YuNet face detection — geometry only, no identity, and
     licence-clean so it can always run
   - `FaceRecognitionTreatmentHandler.java`: embeds each detected face and groups it into a person.
     Consumes the detection rows; needs an operator-selected embedding model

   **The face pipeline is two treatments on purpose.** Detection is unencumbered; *recognition*
   needs an embedding model whose weights are almost always non-commercial, so the model is an
   instance-global operator choice rather than something shipped enabled:
   - `FaceEmbeddingModelService`: the picker — mode (NONE / BUILTIN / CUSTOM), URL, and the values
     derived from the model at set time (content hash, output dim, preprocessing, grouping
     threshold). Stored in `SYSTEM_SETTINGS`, which the `userSettings` GraphQL query does not expose.
   - `FaceModelProbe`: verifies a bring-your-own model against checked-in aligned crops before it can
     be stored, so "a stored model is a verified model" holds.
   - `FaceGrouping`: the pure nearest-centroid grouping decision. Domain logic lives in the handler;
     `FaceDao`/`FaceGroupDao` only persist what they are handed.
   - `TreatmentUtils.fetchModelByContentHash`: every ONNX model is cached at
     `~/.enterfind/models/<sha256>/model.onnx` and revalidated with a conditional GET.
   - The table-level design decisions — why detections anchor on the item treatment, why embeddings
     carry a model hash, why there is no stored face count — are documented in the javadoc on the
     corresponding `create*Table` methods in `Schema.java`. User-facing behaviour is in
     `docs/treatment-face-detection.md` and `docs/treatment-face-recognition.md`.

5. **Models** (`src/main/java/enterfind/server/model/`)
   - Domain models matching database tables
   - GraphQL input types in `model/input/`

6. **MCP Server** (`src/main/java/enterfind/server/mcp/`)
   - Model Context Protocol implementation for AI integration
   - Exposes GraphQL introspection and execution as MCP tools

## Inference engines: what is in the jar, and what must stay out

DJL is present with the **ONNX Runtime engine only** (`ai.djl:api` + `ai.djl.onnxruntime:onnxruntime-engine`). PyTorch (libtorch) is deliberately absent and must stay absent — `pom.xml` carries the full reasoning next to the dependency.

The constraint this places on code here: **a translator must do its tensor work over `toFloatArray()` / `manager.create(...)`, never over NDArray ops.** `OrtNDArray` implements only a handful of ops and DJL silently delegates the rest to the default engine, so reaching for NDArray arithmetic re-introduces a need for libtorch — which costs a ~432 MB download and SIGILLs on pre-ARMv8.1 CPUs. The failure mode is a runtime crash on hardware you probably are not testing on, not a compile error.

For which ARM hardware the image targets, see `docs/arm-hardware.md`.

## Database Schema

Schema creation and migrations live in `Schema.java`, run from `initializeSchema()` at `@PostConstruct` on every boot. Migrations are forward-only and guard on a data shape they themselves destroy, so each runs exactly once and is a no-op on later boots — follow that idiom when adding one.

### Core Tables

**DATA_SOURCES**
- Primary key: `id` (VARCHAR(16))
- Fields: `name`, `type`, `config` (JSONB), `cron_pattern`, `status`, `date_last_scanned`
- Status: IDLE, SCANNING, PROCESSING

**ITEMS**
- Primary key: `id` (VARCHAR(16))
- Fields: `datasource_id`, `name`, `path`, `size_bytes`, `content_hash`, `mime_type`, `time_created`, `time_modified`, `date_scanned`
- Foreign key: `datasource_id` → DATA_SOURCES(id) ON DELETE CASCADE
- **Important**: `path` is slash-separated, lower-cased, excludes the item's own name

**FOLDERS**
- Same structure as ITEMS but without `size_bytes`, `content_hash`, `mime_type`
- Represents directory hierarchy
- `scan_failure_count`: consecutive failed attempts to list this folder, reset to 0 on success, so
  non-zero means its contents are currently stale

**DATASOURCE_TREATMENTS**
- Links treatments to data sources: NAME_AND_PATH, CONTENT, OCR, OBJECT_DETECTION, FACE_DETECTION,
  FACE_RECOGNITION (the full list is the `Treatments` enum)
- Fields: `id`, `datasource_id`, `treatment`, `config` (JSONB)
- **FACE_RECOGNITION depends on FACE_DETECTION**: adding it auto-enables detection, removing detection
  removes recognition, and its rows are only claimable once the same item's detection row is DONE

**ITEM_TREATMENTS**
- Tracks treatment execution status per item
- Fields: `id`, `datasource_treatment_id`, `item_id`, `status`, `version`, `date_created`, `date_modified`, `failure_count`
- Status: NEW, UPDATED, INPROGRESS, **DONE**, FAILED — the terminal success value is `DONE`, not
  `COMPLETED`; SQL written against `COMPLETED` silently matches nothing
- NEW, UPDATED and FAILED are all *pending*: the claim query treats FAILED as retryable, up to the
  `TreatmentMaxFailureCount` setting. A partial index repeats that status list literally, so changing
  one without the other silently reverts the query plan with no error

**ITEM_TREATMENT_RESULTS**
- Stores key-value results from treatments — metadata-shaped values
- Fields: `id`, `item_treatment_id`, `key`, `value`
- Examples: `detected_objects`, and the name/path keys

**ITEM_TREATMENT_RESULTS_CONTENT**
- Extracted document text, split out from the table above because it is indexed differently: a
  trigger maintains `value_tsv` over the first `TreatmentUtils.FTS_CHUNK_SIZE` characters, with a GIN
  index on it. This split is why LIKE means substring on metadata and whole-word on content

### Indexes
- Unique indexes on (datasource_id, path, name) for items and folders
- GIN indexes with pg_trgm on name and path for fuzzy search, and on metadata values
- GIN index on the content tsvector
- Status indexes on item_treatments, including a partial index over pending rows only, so the claim
  query's cost shrinks as the backlog drains rather than growing

## Path Handling Convention

**CRITICAL**: The path system is fundamental to the entire application.

### Path Format
- `path`: Slash-separated, lower-cased full path to the item's **parent folder**, or NULL/empty if in root
- `name`: The item or folder's own name (case-preserved)
- `getFullPath()`: Returns `path + "/" + name` (or just `name` if path is empty) — `AbstractItem.java:50`

> Paths were comma-separated historically. `Schema.migratePathSeparatorToSlash()` converted existing
> databases, and it guards on the shape it destroys (commas but no slashes), so it is a no-op after
> the first boot. Any doc, query or test still using commas is stale.

### Examples
```
Root file "readme.txt":
  path = null or ""
  name = "readme.txt"
  fullPath = "readme.txt"

File "documents/report.pdf":
  path = "documents"
  name = "report.pdf"
  fullPath = "documents/report.pdf"

Folder "documents/2024/invoices":
  path = "documents/2024"
  name = "invoices"
  fullPath = "documents/2024/invoices"
```

### Path Concatenation Rules
When building parent folder full path in SQL queries:
```sql
CASE
    WHEN parent.path IS NULL OR parent.path = '' THEN parent.name
    ELSE parent.path || '/' || parent.name
END
```

**Never** use `parent.path || '/' || parent.name` without the CASE statement for root-level folders!

See `findOrphanedFolders` and `findOrphanedItemIds` in `ItemDao.java` (both around the `||` joins).

Related: every by-path method in `ItemDao` needs the shared `path IS NULL` constant with its CAST.
Comparing a bare parameter to NULL is untypeable in PostgreSQL and fails to *prepare*, so the
root-level branch throws rather than merely failing to match.

## Scanner Workflow

### Scan Process
1. **Initialization**: Scanner sets data source status to SCANNING
2. **Recursive Scan**: Uses ForkJoinPool with `ScanTask` (RecursiveAction)
   - Compares source files vs database state
   - Creates new items/folders
   - Updates modified items (size changed or time_modified diff > 10ms)
   - Deletes items/folders no longer in source
3. **Orphan Cleanup**: Removes items/folders whose parent path no longer exists
4. **Treatment Creation**: Creates item_treatments for new items
5. **Status Update**: Sets status to IDLE, updates last_scanned timestamp

A folder that fails to list keeps its subtree indexed and is retried next scan — it is never read as
"empty, therefore deleted" — and increments `FOLDERS.scan_failure_count`. The scan **root** is
deliberately different: a failed root listing propagates, skipping both orphan cleanup and the
last-scanned update, so a stale `dateLastScanned` is the operator-visible signal.

### Treatment Processing

> **Alpha profile has no treatment scheduler.** `TreatmentProcessorScheduler` is `@Profile("!alpha")`,
> so on a dev box nothing polls for outstanding work — treatments run only via `Scanner.scanAsync`
> after a scan. Work queued *without* a scan (e.g. changing the face embedding model, which marks
> recognition rows UPDATED) therefore sits pending until the next scan. Under any non-alpha profile
> the scheduler picks it up on its own. Trigger a no-op `scanDataSource` locally to pump the queue.

1. **TreatmentProcessor**: Runs asynchronously after scan
2. Claims item_treatments in a pending status (NEW, UPDATED or FAILED)
3. Executes corresponding TreatmentHandler
4. Stores results in item_treatment_results (or ..._CONTENT for document text)
5. Updates status to **DONE** or FAILED

Startup repairs crash leftovers: `Schema.resetStuckDataSources()` moves data sources from SCANNING
back to IDLE and item treatments from INPROGRESS to FAILED, logging both at WARN with counts.

### Parallel Execution
- Scanner uses ForkJoinPool with parallelism = `CPUs - 2`
- TreatmentProcessor uses a configurable concurrent semaphore, with per-treatment-type caps for the
  heavy handlers (OCR and the ONNX ones) — defaults set in `Schema.createSettings()`
- Database operations are thread-safe via DBAccess connection pooling

## Testing Conventions

### Test Organization
- Integration tests in `src/test/java/enterfind/server/`
- Mirror main package structure
- Naming: `*IntegrationTest.java`, `*Test.java`

### AbstractScannerIntegrationTest
- Base class for scanner tests (ScannerIntegrationTest, SMBScannerIntegrationTest)
- Provides: `@BeforeAll` setup, database cleanup, common assertions
- Subclasses define data source type and provider

### GraphQL Testing
Drive the API through `GraphQLTestClient` (used by 17 test classes) rather than hand-rolling HTTP:

```java
GraphQLTestClient client = new GraphQLTestClient(port);
JsonNode response = client.executeAndParse("query { about { name version } }");
```

It also carries the auth token (`setAuthToken` / `clearAuthToken`) for tests that exercise
authenticated endpoints.

### Test Database
- Uses PostgreSQL running inside the devcontainer
- Connection: `psql -U enterfind_test -d enterfind_test` (or with PGPASSWORD environment variable)
- Database automatically initialized on devcontainer creation
- Tests clean database in setup: DELETE FROM tables in reverse FK order

### Test Data Sources
- **LocalFilesDataProvider**: Scans local filesystem (src/test/resources/testshare)
- **RemoteFileSystemDataProvider**: Scans Samba share running in devcontainer
  - SMB URL: `smb://localhost/scratch` (the share in `smb.conf` is `[scratch]`)
  - Credentials: `smbtest:smbtest123` — `SmbTestHelper.PASSWORD` is the authority
  - Share path: `/samba/shares/scratch`
- **S3DataProvider**: Scans a MinIO bucket running in the devcontainer
  - Endpoint `http://localhost:9000`, credentials `minioadmin:minioadmin`

**Both Samba and MinIO must be started by hand before a full `mvn test`, and neither survives a
devcontainer restart:**

```bash
/usr/local/bin/start-samba.sh    # smbd + nmbd, share /samba/shares/scratch
/usr/local/bin/start-minio.sh    # localhost:9000
```

Without them exactly six classes fail and nothing else does — `SMBUploadIntegrationTest`,
`SMBScannerIntegrationTest`, `SMBDataProviderCommaTest`, `SMBCacheInvalidationIntegrationTest`,
`S3UploadIntegrationTest`, and one test in `DataSourceGraphQLIntegrationTest`. That exact set is the
signal the services are down, not that the branch is broken.

**Verify them properly — `pgrep -f smbd` lies**, because the pattern matches its own command line.
Use `pgrep -c '^smbd$'` / `pgrep -c '^minio$'`, or
`curl -s -o /dev/null -w '%{http_code}' http://localhost:9000/minio/health/live` (expect 200).
`smbclient`, `netstat` and `ss` are not installed.

**Leftover share directory:** SMB tests leave `/samba/shares/scratch/enterfind-server-tests`
behind, owned by `smbtest`. `start-samba.sh` must change the owner of the share root only, never
recursively: a `chown -R` changes that leftover folder's owner to `nobody` (mode 755), after which
every SMB subdirectory creation fails with `STATUS_ACCESS_DENIED`. If a container still built from
the older, recursive script shows this, `rm -rf /samba/shares/scratch/enterfind-server-tests` and
re-run.

### Running Tests
```bash
mvn test                          # All tests
mvn test -Dtest=ScannerIntegrationTest  # Specific test
```

## Code Conventions

### Exceptions

Throw the project's own exceptions from `enterfind.server.exception` — `GraphQLConfig` maps each to a
typed GraphQL error, so the right choice is what gives an API caller a usable `errorType`/`errorCode`
instead of a generic failure:

| Exception | GraphQL error | Use for |
|---|---|---|
| `InvalidArgumentException` | `BAD_REQUEST` / `INVALID_ARGUMENT` | bad user input — an unknown enum value, a blank URL, an out-of-range setting |
| `NotFoundException` | `NOT_FOUND` / `RESOURCE_NOT_FOUND` | an entity that does not exist |
| `SystemIssueException` | `INTERNAL_ERROR` / `SYSTEM_ISSUE` | internal failures the caller cannot fix |

All three are unchecked. A bare `IllegalArgumentException` or `RuntimeException` reaches the client as
an untyped internal error, which is almost never what you want from a controller or a service.

### Records vs classes

- **Records** for immutable data: model types and GraphQL inputs (`LocalFileSystemDataSource`,
  `CreateLocalFileSystemDataSourceInput`, `FaceGroupMutation`, `TreatmentOutput`).
- **Classes** for stateful or Spring-managed components: DAOs, processors, treatment handlers.

### Enum behaviour pattern

`DataSourceType` encapsulates type-specific behaviour in the constants themselves rather than in
`switch` statements, so adding a data source type is a compile error until every behaviour is
supplied:

```java
public enum DataSourceType {
    LOCAL_FILESYSTEM {
        @Override
        public DataProviderInterface getDataProviderForTypeAndSource(DataSourceInterface ds) {
            return new LocalFilesDataProvider((LocalFileSystemDataSource) ds);
        }
    }
    // ... abstract method declarations
}
```

`Treatments` is deliberately **not** this shape: each constant carries a handler *class* resolved via
`SpringContext.getBean` at call time, so handlers can be Spring components with injected
dependencies, and nothing is instantiated when the enum class loads. See
[Adding a New Treatment](#adding-a-new-treatment).

### Data provider caching

`DataProviderInterface` instances are cached per datasource id in `DataSourceType`'s
`DATA_PROVIDER_CACHE` (a `ConcurrentHashMap`), because SMB and S3 providers hold connections worth
reusing across scans. Two consequences:

- Tests that create providers must call `DataSourceType.shutdownAllProviders()` in cleanup, or
  connections leak across test classes (see `AbstractUploadIntegrationTest`).
- Changing a datasource's config does not by itself invalidate a cached provider — the entry is keyed
  on the datasource id.

## Common Development Workflows

### Adding a New Treatment

1. **Create Handler** in `scan/treatment/` as a Spring `@Component`. A handler **returns** its
   results rather than writing them: `TreatmentOutput` is the channel, and `TreatmentDao` persists
   the whole output in one transaction. Never write to a DAO from a handler.
```java
@Component
public class MyTreatmentHandler implements TreatmentHandler {
    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider,
                                 DataSourceInterface dataSource, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();
        // ... extract, then hand the results back
        return new TreatmentOutput(results);
    }
}
```
   Also implement `canProcess(Item)` if the treatment only applies to some items — it is consulted at
   scan time so no item_treatments row is created for items the handler would skip anyway.

2. **Register in the `Treatments` enum**, which carries the handler *class* and resolves the bean
   through `SpringContext.getBean` at call time — so the handler must be a `@Component`, not a
   `new`-ed instance. There is no handler map in `TreatmentProcessor` to update:
```java
MY_TREATMENT(MyTreatmentHandler.class, "My Treatment", "What it does")
```

3. **Add to the GraphQL `TreatmentType` enum** in `schema.graphqls` so it can be enabled from the UI

4. **Add a migration** in `Schema.java` if schema changes are needed, following the guard-on-a-shape
   idiom described under Database Schema

### Adding a New DAO Method

1. **Write SQL** using text blocks:
```java
public List<Item> findItemsByPath(String datasourceId, String path) {
    String sql = """
        SELECT id, datasource_id, name, path, size_bytes
        FROM items
        WHERE datasource_id = ?
          AND path = ?
        ORDER BY name
        """;

    return dbAccess.query(sql, (rs, rowNum) -> {
        Item item = new Item();
        item.setId(rs.getString("id"));
        // ... map other fields
        return item;
    }, datasourceId, path);
}
```

2. **Handle NULLs** explicitly for timestamps:
```java
Timestamp ts = rs.getTimestamp("time_modified");
if (ts != null) {
    item.setTimeModified(ts.toLocalDateTime());
}
```

3. **Use prepared statements** - never concatenate user input into SQL

4. **Give every ORDER BY a tiebreaker.** Items scanned together share a `time_modified`, so ordering
   on it alone lets pagination drop and repeat rows across pages.

### Adding GraphQL Queries

1. **Update schema** in `src/main/resources/graphql/schema.graphqls`
2. **Create resolver** method in appropriate controller class
3. **Add DAO method** if database access needed
4. **Write integration test** in GraphQL*IntegrationTest

**Write descriptions on anything a caller has to reason about.** `schema.graphqls` carries them on
the search surface, and MCP clients inherit them automatically because the introspection query
already asks for descriptions on arguments, input fields and enum values. The schema is therefore the
contract for both humans and agents — it is where search semantics and their documented limits live,
and the place to read before writing an example query rather than guessing argument names.

## Common Pitfalls

1. **Path Concatenation**: Always use CASE for root-level folders in SQL
2. **Transaction Management**: DAOs don't manage transactions; caller's responsibility
3. **Null Handling**: Database fields like path, time_created can be NULL
4. **Parallel Scanning**: Don't modify shared state in ScanTask; use thread-safe DAO methods
5. **Treatment Handlers**: Must be idempotent (can be re-run safely)
6. **Test Isolation**: Clean database properly in @BeforeAll or tests will interfere
7. **ID Generation**: Use UUID-based IDs via `UUID.randomUUID().toString().substring(0, 16)`
8. **NDArray ops in translators**: see Inference engines above — use `toFloatArray()` instead

## File References

### Core Files to Understand
- `Scanner.java`: Scanning orchestration and logic
- `ItemDao.java`: Database operations for items/folders
- `SearchDAO.java`: Search query construction
- `TreatmentProcessor.java`: Treatment execution engine
- `Schema.java`: Database schema definition and migrations
- `AbstractScannerIntegrationTest.java`: Test base class
- `AbstractItem.java`: Path handling via getFullPath()
- `schema.graphqls`: the API contract, descriptions included

### Configuration Files
- `application.properties`: Spring Boot config, database connection
- `pom.xml`: Maven dependencies
- `docker-compose.traefik-aws.yml`: reference Traefik+AWS Route53 deployment configuration (not used for local development)
- `docker-compose.minimal.yml`: app + Postgres only, the common self-host case
- `.devcontainer/`: Local development environment configuration

## Version Information

- Spring Boot: 4.1.0
- Java: 25 (Temurin/Adoptium)
- PostgreSQL: local development uses the devcontainer's PostgreSQL service
- GraphQL: spring-boot-starter-graphql
- Maven: Latest (in devcontainer)
