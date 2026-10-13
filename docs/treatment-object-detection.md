# Treatment: Object Detection

Identifies objects in images and makes them findable, so you can search a photo library by what's *in* the pictures rather than only by filename or metadata.

## What it does

For each image item, Object Detection runs a vision model that recognizes objects in the picture and records what it found as searchable attributes on the item. After it runs, you can search for items containing a given kind of object and get back the images that show it — turning an otherwise opaque photo collection into something you can query by content.

This is complementary to what [Content](treatment-content.md) does for images: Content extracts a picture's embedded *metadata*, while Object Detection identifies what's actually *depicted* in it.

## When to use it

On sources of images where the subject matter matters for finding things: a photo library, a collection of product shots, screenshots, scanned figures. If a source is documents rather than pictures, this treatment has little to work with — reach for [Content](treatment-content.md) and [OCR](treatment-ocr.md) instead.

## Fields written

Object Detection writes these [attribute keys](item-attributes.md), one pair per object found in the image:

- `objectdetected` — the kind of object recognized (for example `car`, `dog`, `person`). This is a [standard field](item-attributes.md#standard-fields). An image with several objects carries several `objectdetected` values, which is what makes it break down cleanly in a [facet](search.md).
- `objectdetectionconfidence` — the model's confidence in that detection, paired with it.

Like every treatment, it also writes `treatmenttype` identifying itself.

The recognized objects come from a fixed vocabulary of common object classes — everyday things (vehicles, animals, people, furniture, and the like) — not an open-ended list of arbitrary labels. [Face Detection](treatment-face-detection.md) writes into this same `objectdetected` key with the value `face`, so a single "what's in this image" facet can cover both objects and faces.

## Cost and performance

Object Detection is a vision treatment and runs a model over image data, so it's heavier than text treatments. On a large image corpus, expect items to spend meaningful time in **INPROGRESS**; the source's treatment status counts show how far along processing is.

## Configuration

Enable it on the data source and scan. Any model or threshold options a deployment exposes are set in the treatment's configuration on the source.

## Removing it

Removing the Object Detection treatment from a data source also removes the data it produced: the detected-object attributes it added for that source's items are deleted along with the treatment. Those images stop being findable by their detected objects until you re-enable the treatment and re-scan.

## Relationship to other treatments

Object Detection is independent of the face treatments and of the text treatments — it can run alongside any of them on the same source. Pair it with the always-on [Name and Path](treatment-name-and-path.md) so an image is findable by filename and by content, and with [Face Detection](treatment-face-detection.md) / [Face Recognition](treatment-face-recognition.md) if you also want to find images by who's in them.

---

**See also:** [Treatments overview](treatments.md) · [Content](treatment-content.md) · [Face Detection](treatment-face-detection.md) · [Face Recognition](treatment-face-recognition.md) · [Search](search.md)

[← Back to documentation index](../README.md)
