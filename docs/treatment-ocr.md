# Treatment: OCR

Reads text off scanned pages and images — documents that have no digital text layer — and makes that text searchable. OCR (optical character recognition) is what lets Enterfind find words inside a scanned contract, a photographed receipt, or a fax saved as an image.

## What it does

For each applicable item, OCR recognizes the text visible in the image and indexes it as searchable content. After OCR runs, a scanned document behaves in search like a born-digital one: search for a phrase printed on the page and the scan comes back, even though the file contains no text characters, only pixels.

## When to use it

Whenever a source contains scanned or photographed documents:

- scanned PDFs (a photocopier or scanner output, with no text layer),
- photos of documents, receipts, IDs, whiteboards,
- image files that happen to contain text.

If your corpus is entirely born-digital (exported PDFs, office documents), you don't need OCR — [Content](treatment-content.md) already has real text to extract. If you're not sure what's in the corpus, enabling both is safe: Content handles the files with a text layer, OCR handles the rest.

## OCR vs. Content

[Content](treatment-content.md) extracts text that's already stored as text in the file (and metadata from pictures and videos). OCR *recognizes* text from imagery where no such layer exists. They cover different inputs and are designed to run together — a mixed corpus typically enables both, and even a single PDF can need both when some pages are digital and others are scanned images.

For a scanned PDF that turns out to already carry a real text layer, OCR steps aside and lets Content handle it, rather than recognizing text that's already extractable.

## Fields written

OCR writes these [attribute keys](item-attributes.md):

- `ocr-content` — the recognized text. Large results are split across numbered keys — `ocr-content-0`, `ocr-content-1`, and so on — so the full text stays searchable; a short result uses the single key `ocr-content`.
- `content_truncated` — set to `true` only when the recognized text exceeded the size limit and was cut off. Its absence means the full text was captured.

Like every treatment, it also writes `treatmenttype` identifying itself.

Note that OCR's text lands under `ocr-content`, distinct from Content's `content` — so recognized text and extracted text stay tellable apart, even on a document that went through both.

## Cost and performance

OCR is heavier than text extraction — it processes imagery rather than reading a text stream — so on a large corpus it takes longer, and its items spend more time in the **INPROGRESS** state before reaching **DONE**. Watch the treatment status counts on the source's stats to gauge progress. This is expected; it's the price of making pixels searchable.

## Supported scripts

There is no language to configure. OCR first detects the page's *script* — the writing system, not the language — and then reads the page with the model chosen for that script:

| Detected script | Model | Notes |
|---|---|---|
| Latin | English, French, German and Spanish together | Other Latin-script languages (Polish, Czech, Portuguese, Italian, Dutch, …) are read with these four, and lose accented letters and some words |
| Cyrillic | Cyrillic script model | Russian, Ukrainian, Bulgarian, Serbian, … |
| Arabic | Arabic script model | Arabic, Persian, Urdu, … |
| Hebrew | Hebrew | |
| Greek | Greek script model | |
| Han (Chinese) | Simplified Chinese | Traditional Chinese is read with the Simplified model, and about half of its characters come out wrong |
| Japanese | Japanese | |
| Hangul (Korean) | Korean | English words inside Korean text come out as garbage |
| Devanagari | Devanagari script model | Hindi, Marathi, Nepali, … |
| Bengali | Bengali | |
| Tamil | Tamil | |
| Telugu | Telugu script model | |
| Kannada | Kannada script model | |
| Thai | Thai | |

A script not in this table is read with the English model. The models were picked to keep OCR time close to a single-language pass, so where a slower model would have been more accurate, the faster one was kept. The listed limitations are the cost of that choice.

Script detection needs a reasonable amount of text, and it can guess wrong. A Chinese page with many English product names can be detected as Latin, and pages with very little text fall back to the English model.

The Docker image and the [installer](installation.md#installer-no-docker) come with the models above. Outside them, the server uses whatever Tesseract models are installed. At startup the server logs which model each script will use, and warns about any script with no model installed. Pages in that script are read with the English model.

## Configuration

Enable it on a data source and scan. OCR has no options of its own; the recognition language is chosen automatically, as described above.

## Removing it

Removing the OCR treatment from a data source also removes the data it produced: the recognized text it contributed for that source's items is deleted along with the treatment. Those scans stop matching on their OCR'd text until you re-enable OCR and re-scan.

## Relationship to other treatments

OCR contributes to the same searchable text that [Content](treatment-content.md) builds, so the two are complementary rather than competing. It's independent of the vision treatments ([Object Detection](treatment-object-detection.md), [Face Detection](treatment-face-detection.md), [Face Recognition](treatment-face-recognition.md)) — you can run OCR and object/face detection on the same image source to make it findable by both its text and its visual contents.

---

**See also:** [Treatments overview](treatments.md) · [Content](treatment-content.md) · [Name and Path](treatment-name-and-path.md) · [Search](search.md)

[← Back to documentation index](../README.md)
