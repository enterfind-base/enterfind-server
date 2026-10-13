# Data Sources

A data source tells Enterfind where documents live. You add a source, Enterfind scans it into items, and treatments process those items. This page covers the three source types, how scanning works, and the settings every source shares.

## The three source types

### Local Filesystem

A path on the machine (or container) running Enterfind. The one required setting is the path itself.

- **Local path** — the directory Enterfind reads. In a containerized deployment this is a path *inside the container*, so the folder you care about has to be mounted into the container first; the path you enter is where it's mounted.

Best for documents that already sit on the Enterfind host: a NAS volume, a mounted drive, a synced folder.

### Remote Filesystem (SMB/CIFS)

A network share reached over SMB/CIFS — a Windows share, a NAS share, a Samba server. Settings:

- **Server** — the host to connect to.
- **Domain** — optional; the Windows/AD domain, if any.
- **Username** and **password** — credentials for the share.
- **Share name** — the exported share to mount.
- **Path** — optional subpath within the share to index, if you don't want the whole thing.

Best for documents on another machine that you don't want to mount at the OS level.

### S3-Compatible Bucket

Any storage that speaks the S3 API — Amazon S3, and also MinIO, Cloudflare R2, Backblaze B2, Wasabi, Garage, and others, by pointing at the right endpoint. Settings:

- **Name or URL** — the bucket name, or the endpoint URL for a non-AWS S3-compatible provider.
- **Access key** and **secret key** — the credentials.
- **Path** — optional key prefix to index a subset of the bucket.
- **Region** — optional; required by some providers.

Best for cloud-object storage and self-hosted object stores alike. If you can point it at an S3 endpoint, Enterfind can index it.

## Settings every source shares

- **Name** — a label unique within the instance. You'll refer to the source by this name in search scoping and API calls.
- **Cron pattern** — optional. When set, Enterfind re-scans the source automatically on that schedule (see *Scheduling* below). When empty, the source is scanned only when you trigger it.
- **Skip files** — optional pattern describing files to ignore during a scan. Use it to keep noise (system files, thumbnails, temp files) out of the index. If you're unsure how to phrase a pattern, the [AI Helper](ai-helper.md) can turn a plain-language description into one.

## Scanning

A scan walks the source and reconciles it with what Enterfind already knows:

- Files it hasn't seen become **new** items.
- Files that changed since last scan are marked **updated**.
- Configured treatments then run against the items that need them.

A source is **IDLE** when nothing is running and **SCANNING** while a scan is in progress. You can watch progress live on the source's stats: total item count, how many items sit in each treatment status, and how many previews remain to generate.

### Scanning is incremental and safe to repeat

Re-scanning doesn't reprocess everything from scratch — it picks up what's new or changed. Running a scan after adding files, or after enabling a new treatment, is the normal way to bring the index current. You can also **clear** a source's indexed data if you want to start its processing over, or **delete** the source entirely.

### Scheduling

Set a cron pattern on a source and Enterfind re-scans on that cadence — hourly, nightly, weekly, whatever the expression says. This is how you keep a live folder or bucket continuously current without manual scans. If cron syntax isn't at your fingertips, the [AI Helper](ai-helper.md) can translate "every night at 2am" into the expression, and explain an existing expression back to you in plain language.

## Testing a source before you commit

Before (or after) creating a source, you can **test** it: Enterfind connects with the settings you've given and reports back whether it succeeded, along with a sample of the files and folders it can see. This is the fast way to catch a wrong credential, a bad share name, or a mistyped endpoint before you scan.

## What comes next

A scanned source is a list of items with names, folders, sizes, and types — already enough for filename search. To make the *contents* findable, and to extract more, enable [Treatments](treatments.md).

---

**See also:** [Treatments](treatments.md) · [Item Attributes](item-attributes.md) · [Search](search.md) · [AI Helper](ai-helper.md) · [Installation](installation.md)

[← Back to documentation index](../README.md)
