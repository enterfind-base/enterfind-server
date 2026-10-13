# Search

Once a data source is scanned and its treatments have run, its items are searchable. Enterfind's search combines free text, structured conditions, folder scoping, and facets — used together or separately.

## The pieces of a search

A search is built from a few independent parts, any of which you can leave out:

- **Search string** — free text matched against items, including the text that [Content](treatment-content.md) and [OCR](treatment-ocr.md) extracted. This is the "type what you're looking for" box. Whitespace separates terms, and an item matches when *every* term is found somewhere on it — each term independently, so `invoice 2024` finds a file named `invoice.pdf` whose text mentions 2024.
- **Search conditions** — structured filters on item attributes (see below).
- **Folder** — scopes the search to a folder within a data source, so you can search just one part of a source.
- **Name** — scopes to a specific data source by name.
- **Sort direction** — ascending or descending.

Results come back as a page of items with a total hit count, so you can page through large result sets (skip *n*, take *m*).

## Search conditions

A condition is a structured filter with three parts:

- a **key** — the attribute to filter on,
- an **operation**, and
- a **value**.

The available operations are **equal**, **not-equal**, **greater**, **less**, **like**, and **not-like**. The comparison operators suit numeric and date-like attributes. Multiple conditions combine to narrow a result set, and they work alongside the free-text search string rather than instead of it.

`like` means *contains all of these words*: `Microsoft Word` matches a value of `Microsoft Office Word` or `Microsoft_Word_-_report.pdf`, because the words need not be adjacent or in order. `not-like` is its exact opposite.

### Quoting

In a condition value and in the search string alike, wrap words in double quotes to require them **contiguous and in that order**: `"Microsoft Word"` matches only the literal phrase. Quoting is the only way to ask for adjacency. On extracted document text it is also the slow option — the cost grows with how many documents contain those words at all, so a common phrase can take a few seconds.

### What a condition ranges over

This is the one difference between the two that's worth internalising. A **condition** names one key, so all of its words must appear in a single value of that key: `name like annual report` means both words in one filename, never one word in the name and the other in the path. The **search string** ranges over the whole item, so its terms can be satisfied from different places.

### Two things to know about matching

- **Filenames, paths and other metadata match characters; extracted document text matches whole words.** `istribut` finds `distributed` in a filename, and nothing in document text. For document text, use a prefix instead — `distribut` in the search string finds "distributed", "distribution" and "distributing". There is no mid-word search inside document text.
- **Negation includes items that lack the attribute entirely.** `not-equal` and `not-like` are satisfied by an item that simply has no value for that key, not only by one whose value doesn't match.

The keys you can filter on are the [item attributes](item-attributes.md) present in your corpus — filenames and paths, extracted content, and whatever the treatments you've enabled have produced (detected objects, face labels, and so on). Enterfind can tell you which attribute keys exist, and which keys are available given a search you've already started, so you can build a filter against what's actually there. See [Item Attributes](item-attributes.md) for how to discover them.

## Facets

A facet is a breakdown of a result set by the values of one attribute key: each distinct value and how many items carry it. Facets are what power drill-down search — see the distribution of a key across your results, pick a value, and narrow. You request facets one key at a time.

## Saved queries

A search you run often can be saved and recalled later by name, so you don't have to rebuild it each time. Saved queries are personal to the user who created them. See [Saved Queries](saved-queries.md) for details.

## How treatments shape what's searchable

Search is only as rich as the treatments you've run:

- The always-on [Name and Path](treatment-name-and-path.md) treatment means filenames and folders are always searchable.
- Add [Content](treatment-content.md) and [OCR](treatment-ocr.md), and the text *inside* documents (born-digital and scanned), plus the metadata of pictures and videos, becomes searchable via the search string.
- Add the vision treatments ([Object Detection](treatment-object-detection.md), [Face Detection](treatment-face-detection.md), [Face Recognition](treatment-face-recognition.md)), and images become findable by their objects and by the people in them, via conditions and facets on the attributes those treatments produce.

If something you expect to find isn't turning up, the first question is usually whether the treatment that would make it findable is enabled and has finished — check the treatment status counts on the source.

## Natural-language search

If you'd rather describe what you want than build conditions by hand, the [AI Helper](ai-helper.md) can interpret a plain-language query into a structured search against your available attribute keys. And an external [AI assistant](ai-access.md) connected to Enterfind searches this same way on your behalf.

---

**See also:** [Item Attributes](item-attributes.md) · [Saved Queries](saved-queries.md) · [Treatments](treatments.md) · [AI Helper](ai-helper.md) · [The API](api.md)

[← Back to documentation index](../README.md)
