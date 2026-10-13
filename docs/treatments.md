# Treatments

A treatment is a unit of processing Enterfind applies to the items in a data source. Scanning discovers files; treatments turn them into rich, searchable content — pulling out text and metadata, reading scanned pages, detecting what's in images.

Most treatments are enabled **per data source**, so you can process a folder of contracts differently from a folder of photos. Enable a treatment, scan, and it runs against the items that need it. One treatment — Name and Path — is required and always on (below).

## The six treatments

Enterfind ships six treatments. Each has its own page:

- **[Name and Path](treatment-name-and-path.md)** — makes filenames and folder paths searchable. **Required and always enabled**, on every source; the baseline everything else builds on.
- **[Content](treatment-content.md)** — extracts the text and metadata inside documents (including office documents, and metadata from pictures and videos) so their contents are searchable.
- **[OCR](treatment-ocr.md)** — reads text off scanned pages and images, for documents that have no digital text layer.
- **[Object Detection](treatment-object-detection.md)** — identifies objects depicted in images and makes them findable.
- **[Face Detection](treatment-face-detection.md)** — finds faces in images.
- **[Face Recognition](treatment-face-recognition.md)** — groups and labels the faces that detection found, so you can search by person.

You can enable any combination of the other five on a source. A folder of scanned invoices might add OCR + Content; a photo library might add Object Detection + Face Recognition.

## What a new data source starts with

Creating a data source automatically enables **Name and Path** on it. You don't add it, and you can't remove it — in the source's treatment list its checkbox is fixed on and marked as required. That guarantees every indexed item is findable by what it's called, whatever else is enabled. Every other treatment is opt-in.

## Dependencies between treatments

Some treatments build on others, and Enterfind wires the dependency up for you:

- **[Face Recognition](treatment-face-recognition.md) requires [Face Detection](treatment-face-detection.md).** Enabling recognition on a source **automatically enables detection** on that source too — recognition has faces to work with only because detection found them. You don't add detection by hand. The dependency also runs in reverse when removing: **removing Face Detection from a source removes Face Recognition with it.**
- Face Recognition additionally requires an **embedding model** configured for the instance. Enabling recognition without one is rejected rather than silently stalling — see the [Face Recognition](treatment-face-recognition.md) page.
- **[Content](treatment-content.md) and [OCR](treatment-ocr.md)** both contribute to a document's searchable text, from the embedded layer and from imagery respectively; they're independent but commonly run together.

You don't have to sequence anything manually — enable what you want and Enterfind runs the treatments in the right order.

## The treatment status model

Every item tracks where it stands for the treatments configured on its source. There are five states:

- **NEW** — the item was just discovered and hasn't been processed yet.
- **UPDATED** — the item changed since it was last processed and needs reprocessing.
- **INPROGRESS** — a treatment is running against it now.
- **DONE** — all configured treatments have completed for this item.
- **FAILED** — a treatment could not complete for this item.

A data source's stats show the count of items in each state. This is how you read progress: right after enabling a treatment and scanning, items move from NEW through INPROGRESS to DONE. If items pile up in FAILED, something about those specific files or that treatment's configuration needs attention.

Processing happens in the background after a scan. Large corpora, or heavy treatments like OCR and the vision treatments, take time — the status counts are how you tell whether the work has caught up.

A treatment is only queued for the items it can actually handle: OCR, for example, doesn't create work for a file that is neither an image nor a PDF. So an item reaching DONE means every treatment applicable to it has finished, not that every enabled treatment ran on it.

## Adding and removing treatments

Treatments are managed per source. When you add a treatment to a source, it applies to that source's items on the next processing pass; existing items are picked up, not just future ones.

**Removing a treatment also removes the data it produced.** When you remove a treatment from a source, the attributes and results it contributed for that source's items are deleted along with it — search over that source stops matching on that treatment's output until you re-enable it and re-scan. Each treatment's own page states exactly what data goes away when it's removed. (Name and Path is the exception: it can't be removed at all.)

Some treatments take a configuration (for example, which model or language to use) — those details live on each treatment's own page.

---

**See also:** [Data Sources](datasources.md) · [Item Attributes](item-attributes.md) · [Search](search.md)

[← Back to documentation index](../README.md)
