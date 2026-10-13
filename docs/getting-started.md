# Getting Started

Enterfind turns a pile of documents into a searchable corpus. This page walks the shape of that process end to end, so the rest of the documentation has somewhere to hang.

If you don't have an instance running yet, start with [Installation](installation.md), then come back here.

## The mental model

Enterfind works in five moves:

1. **Add a data source.** Tell Enterfind where your documents live — a folder on the machine, a network share, or an S3-compatible bucket. See [Data Sources](datasources.md).
2. **Scan it.** Enterfind walks the source and records every file it finds as an *item*. Scanning can be run on demand or on a schedule.
3. **Choose treatments.** A treatment is a unit of processing applied to each item — extracting its text, running OCR on scanned pages, detecting objects or faces. You enable the treatments you want per data source (filename and path search is always on). See [Treatments](treatments.md).
4. **Search.** Once items are scanned and treated, they're findable — by filename and path, by their text content, by extracted attributes, and by facets. See [Search](search.md).
5. **Connect an AI assistant (optional).** Because everything Enterfind does is exposed through one API, an assistant like Claude or ChatGPT can search and retrieve your documents the same way the web UI does. See [Connecting AI Assistants](ai-access.md).

Everything after step 2 is incremental: add a treatment later and it runs against items already scanned; add files to the source and the next scan picks them up.

## Your first corpus

A minimal first run:

1. Open the web UI (by default at `http://localhost:8080`).
2. Go to **Sources** and create a **Local Filesystem** data source pointing at a folder of documents.
3. Scan it. Watch the item count climb on the source's stats.
4. Filename and path search works immediately (the [Name and Path](treatment-name-and-path.md) treatment is always on). Add **Content** for full-text search over document contents, and **OCR** if your folder has scanned PDFs or images of text.
5. Go to search and look for something you know is in there.

That's a working instance. From here you can add more sources, layer on more treatments, set up scheduled re-scans, and — if you want your documents reachable from an AI assistant — enable [Remote Access](remote-access.md) and connect one.

## Where to go next

- Not sure what a term means? [Concepts](concepts.md) defines the vocabulary in one place.
- Want the files kept current automatically? Data sources take a cron schedule — see [Data Sources](datasources.md).
- Multiple people using one instance? See [Users and Access Control](users-and-access.md).

---

**See also:** [Installation](installation.md) · [Concepts](concepts.md) · [Data Sources](datasources.md) · [Treatments](treatments.md) · [Search](search.md)

[← Back to documentation index](../README.md)
