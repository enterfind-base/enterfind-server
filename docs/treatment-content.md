# Treatment: Content

Extracts the text and metadata inside documents so their contents — not just their filenames — are searchable. This is what turns Enterfind from a filename finder into a full-text search engine over your corpus.

## What it does

For each item, Content pulls out the document's embedded text and metadata and indexes it. After Content runs, a search for a phrase that appears *inside* a document finds that document, even if the phrase is nowhere in its filename.

What Content can extract depends on the file. Some categories worth calling out separately:

- **Documents with a text layer** — PDFs that carry real text, and plain-text files. The body text is extracted and indexed.
- **Office documents** — word-processor files, spreadsheets, and presentations. Their text content is extracted so the words inside them are searchable, the same as any other document.
- **Metadata from pictures and videos** — image and video files usually have no body text, but they carry embedded metadata (things like capture dates, camera or device details, and other properties stored in the file). Content extracts that metadata so a picture or video is findable by it. To make pictures findable by *what's depicted in them* rather than their metadata, use the vision treatments — [Object Detection](treatment-object-detection.md) and the face treatments.

## When to use it

Whenever the items in a source contain text or metadata you'd want to search for — which is nearly always. For document corpora this is the treatment that does the heavy lifting, and it works together with the always-on [Name and Path](treatment-name-and-path.md): one makes the name findable, the other makes the contents findable.

## Fields written

Content writes these [attribute keys](item-attributes.md):

- `content` — the extracted body text. Large documents are split across numbered keys — `content-0`, `content-1`, and so on — so the full text stays searchable; a document short enough to fit uses the single key `content`.
- `content-type` — the MIME type as detected while parsing (a [standard field](item-attributes.md#standard-fields)). Content updates the item's type from this when its own detection is more accurate than what the scan recorded.
- `content_truncated` — set to `true` only when the document's text exceeded the extraction size limit and was cut off. Its absence means the full text was captured.
- **Document metadata keys** — whatever properties the document itself exposes (author, title, creation date, camera and capture details for images, and so on). These are named as the document exposes them, so the exact keys vary by file type. Purely internal parser fields and unusable binary values are dropped rather than indexed.

Like every treatment, it also writes `treatmenttype` identifying itself.

## Content vs. OCR

Content reads text that's already stored *as text* in the file. It cannot read text that exists only as pixels — a scanned page, a photographed receipt, an image of a document. For those you need [OCR](treatment-ocr.md), which recognizes text from the image itself.

The two are complementary, not either/or. A realistic document source often enables both: Content handles the born-digital files (and the metadata of images/videos), OCR handles the scanned ones. A single PDF can even need both — digital text on some pages, scanned images on others. Note the two use *different* keys for their text — Content writes `content`, OCR writes `ocr-content` — so you can tell extracted text apart from recognized text.

## Configuration

None required for typical use. Enable it on a data source and scan.

## Removing it

Removing the Content treatment from a data source also removes the data it produced: the extracted text and metadata it contributed for that source's items are deleted along with the treatment. Search over that source will no longer match on content until you re-enable it and re-scan.

## Relationship to other treatments

Content works alongside [OCR](treatment-ocr.md) to build the full searchable text of a document — Content from the embedded layer, OCR from the imagery — so enabling both on a mixed corpus is the norm. It's independent of the vision treatments, which add what's *depicted* in images rather than what's embedded as text or metadata.

---

**See also:** [Treatments overview](treatments.md) · [Name and Path](treatment-name-and-path.md) · [OCR](treatment-ocr.md) · [Object Detection](treatment-object-detection.md) · [Search](search.md)

[← Back to documentation index](../README.md)
