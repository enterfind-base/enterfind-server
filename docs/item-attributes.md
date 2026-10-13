# Item Attributes

An **attribute** is a key/value fact about an item — for example `objectdetected` = `car`, or `content-type` = `application/pdf`. Attributes are how the things a [treatment](treatments.md) discovers about a document become searchable and filterable in a uniform way. If treatments are what *produce* knowledge about your documents, attributes are the shape that knowledge takes.

Attributes are a distinct system from an item's built-in fields, and the difference matters when you search. This page covers both.

## Item fields vs. attributes

Every item has a set of **built-in fields** that exist the moment a file is scanned, before any treatment runs. These are properties of the item itself, and you query them by name through [the API](api.md) on the `Item` type:

- `name`, `folder` — the file's name and the slash-separated folder holding it.
- `sizeBytes` — file size in bytes (note: `sizeBytes`, not `size`).
- `mimeType` — the MIME type, as known at scan time.
- `dataSourceName`, `dataSourceType` — which data source the item came from.
- `dateCreated`, `dateModified` — the file's own timestamps.
- `downloadLink`, `previewLink`, `rawBase64Content` — links to the document behind the item (see [Retrieving the document itself](#retrieving-the-document-itself)).
- `faces` — detected faces with their identity labels, in face-index order (see [Faces](#faces-a-richer-attribute)).

These are always present. They are not attributes, and they are not what facets and attribute-key discovery operate on.

**Attributes**, by contrast, are the key/value facts that treatments write. An item has no attributes until a treatment processes it, and which attribute keys appear depends entirely on which treatments you enabled. Attributes are what you filter on with [search conditions](search.md) and break down with [facets](search.md).

The rest of this page is about attributes.

## Standard fields

Among the attribute keys, a fixed set is designated **standard fields** — the keys Enterfind expects to be broadly useful and surfaces first in the UI. The API exposes this list through the `standardFields` query, so you can retrieve the exact set your build ships with rather than guessing.

Two things about standard fields are easy to get wrong:

- **They are a priority/display hint, not a presence guarantee.** The standard-fields list tells you which keys to expect and show first *when they are present*. A key on the list still only appears on items whose producing treatment actually ran. On a corpus that never ran object detection, `objectdetected` is a standard field with nothing under it.
- **They are attribute keys, not the built-in item fields above.** Some standard fields (`name`, `path`, `datasourcename`, `datasourcetype`) look like the built-in fields and carry the same information, but as *attributes* they are written by the [Name and Path](treatment-name-and-path.md) treatment. The built-in `name`/`folder` fields exist without it; the searchable `name`/`path` attribute keys do not.

The standard fields, in the priority order the query returns them, are: `path`, `name`, `datasourcename`, `datasourcetype`, `content-type`, `treatmenttype`, `detectedface`, `objectdetected`. Everything else a treatment writes is a non-standard attribute key, discovered the same way but not pre-prioritized.

## Which treatment writes which key

Every treatment writes `treatmenttype` (identifying itself) plus its own keys. The full set:

- **[Name and Path](treatment-name-and-path.md)** — `name`, `path`, `datasourcename`, `datasourcetype`. Path is written one component at a time, so each folder level is independently searchable.
- **[Content](treatment-content.md)** — `content` (the extracted text; split into `content-0`, `content-1`, … on large documents), `content-type`, `content_truncated` when the text hit the size limit, plus document metadata keys extracted by Tika (author, dates, and similar, named as the document exposes them).
- **[OCR](treatment-ocr.md)** — `ocr-content` (split into `ocr-content-0`, `ocr-content-1`, … on large documents), and `content_truncated` when the text hit the size limit.
- **[Object Detection](treatment-object-detection.md)** — `objectdetected` (one per detected object) and a paired `objectdetectionconfidence`.
- **[Face Detection](treatment-face-detection.md)** — `objectdetected` = `face` (folding faces into the same "what's in this image" facet), `objectdetectionconfidence`, `detectedfaceconfidence` (a face-only confidence axis), and `facecount`.
- **[Face Recognition](treatment-face-recognition.md)** — `detectedface`, the group label (e.g. a person's name) for each identified face.

Each treatment page documents its keys in detail.

## Inspecting what's there

Because the set of attribute keys is corpus-specific, Enterfind lets you discover it rather than making you guess:

- **All keys in the corpus** (`itemAttributeKeys`) — the full list of attribute keys present across everything indexed. This is the menu of things you can filter or facet on.
- **Keys available for a search in progress** (`availableItemAttributeKeys`) — given a search you've partly built (a search string, some conditions, a folder scope), the keys that actually appear on the matching items. This narrows the menu to what's relevant to where you already are, which is how you build a useful filter without dead ends.
- **A specific item's attributes** (`itemAttributes`) — the key/value pairs on one named item, optionally limited to the keys you care about. This is how you see everything Enterfind knows about a single document.

## How attributes power search

Attributes are the substrate under two of search's core features:

- **[Search conditions](search.md)** filter on an attribute key with an operation and a value — for example, an attribute equals a value, or is greater than one, or matches a pattern.
- **[Facets](search.md)** break a result set down by the values of one attribute key, showing each distinct value and how many items carry it — the mechanism behind drill-down search.

So the attribute keys you discover above are exactly the keys you filter and facet on. A treatment's value shows up in search precisely because it wrote attributes the search layer can see.

## Faces: a richer attribute

Most attributes are flat key/value pairs. Images processed by [face detection](treatment-face-detection.md) and [face recognition](treatment-face-recognition.md) carry something richer: each detected face has a stable index within the image, and — when recognition has labeled it — an identity. The `detectedface` attribute lists the names present in a photo; the `Item` type's `faces` field says which face index carries which name, keyed by the same index the crop link uses. This lets search surface not just "this photo contains a named person" but which face in the photo is whom.

## Retrieving the document itself

Attributes describe an item; separately, an item exposes links to the document behind it — `downloadLink`, `previewLink`, and `rawBase64Content` — so a search result can go from "here's what matches" to "here's the file." Those are built-in fields on the item, reachable through [the API](api.md).

---

**See also:** [Search](search.md) · [Treatments](treatments.md) · [The API](api.md)

[← Back to documentation index](../README.md)
