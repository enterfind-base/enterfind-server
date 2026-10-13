# Treatment: Name and Path

Makes an item's filename and folder path searchable. This is the foundational treatment — it looks only at the name and location, never the contents, so it applies to every file regardless of type.

## Always enabled

Name and Path is required. It's added automatically when you create a data source, and it can't be turned off: in the source's treatment list its checkbox is fixed on and marked as required. You never enable it by hand, and there's nothing to configure.

It's the baseline the rest of the system assumes. Every other treatment is opt-in on top of it.

## What it does

For each item, Name and Path indexes:

- the **filename**, and
- the **folder path** the item sits in within its data source.

That means a search for a word in a filename, or for items under a particular folder, finds them — even for file types Enterfind can't read the insides of (an unknown binary format, an archive, a proprietary file). If Enterfind discovered the file at all, its name and path are findable.

It's also the fallback for content nothing else can index. If a data source is full of files whose contents Enterfind can't extract, Name and Path is still what makes them show up in search.

## Fields written

Name and Path writes these [attribute keys](item-attributes.md) (all of them [standard fields](item-attributes.md#standard-fields)):

- `name` — the filename.
- `path` — the folder path, written one component at a time, so each folder level is independently searchable and facetable.
- `datasourcename` — the name of the data source the item came from.
- `datasourcetype` — the data source's type (for example `LOCAL_FILESYSTEM`).

Like every treatment, it also writes `treatmenttype` identifying itself.

Because this treatment is always on, these four keys are the ones you can count on being present across every source — the dependable backbone of any filter or facet you build.

## Why it can't be turned off

These attributes are what make free-text search match a filename. A plain search term is matched against the *attribute values* an item carries, so without the `name` and `path` attributes a file called `invoice-2026.pdf` would not come back for a search for "invoice" unless that word also appeared in its extracted contents. Filtering explicitly by name or folder would still work, but the everyday case — type a word, find the file — would quietly stop working for anything Enterfind can't read inside.

Keeping it mandatory means every indexed item is findable by what it's called, no matter what else is or isn't enabled on its source.

## Configuration

None. It's always active and takes no settings.

## Relationship to other treatments

Name and Path is independent of the others and underlies all of them — every item is findable by name and path, and the other treatments add searchable content on top. Pair the searchable text from [Content](treatment-content.md) and [OCR](treatment-ocr.md), or the visual attributes from the [object](treatment-object-detection.md) and [face](treatment-face-detection.md) treatments, and an item becomes findable by its name *and* by what's inside it.

---

**See also:** [Treatments overview](treatments.md) · [Content](treatment-content.md) · [OCR](treatment-ocr.md) · [Search](search.md)

[← Back to documentation index](../README.md)
