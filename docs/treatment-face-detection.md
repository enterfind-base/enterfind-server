# Treatment: Face Detection

Finds faces in images. This is the first half of Enterfind's people-search capability: detection locates the faces; [Face Recognition](treatment-face-recognition.md) then groups and labels them.

## What it does

For each image item, Face Detection locates the human faces in the picture and records them. Each detected face gets a stable position (a face index) within its item, and Enterfind can produce a crop of each face. On its own, detection tells you *that* an image contains faces and *how many* — it does not yet say *who* they are.

## When to use it

On image sources where you care about the people in the pictures — a family photo archive, event photography, any collection where "find photos with faces in them" or, combined with recognition, "find photos of this person" is useful.

Enable Face Detection on its own if you only need to find images that contain faces. If you want those faces grouped by identity and labeled with names, enable [Face Recognition](treatment-face-recognition.md) — which brings detection along automatically (below).

## Automatically enabled with Face Recognition

Face Detection is a prerequisite for [Face Recognition](treatment-face-recognition.md): recognition can only work with faces detection has already found. So when you enable Face Recognition on a data source, Face Detection is **automatically required and enabled** for that source — you don't have to add it separately. You can still enable Face Detection by itself, without recognition, when all you need is to find images that contain faces.

## Fields written

Face Detection writes these [attribute keys](item-attributes.md), for each face found in the image:

- `objectdetected` = `face` — faces are folded into the same "what's in this image" key that [Object Detection](treatment-object-detection.md) uses, so one facet covers objects and faces together. (`objectdetected` is a [standard field](item-attributes.md#standard-fields).)
- `objectdetectionconfidence` — the detection confidence, under the shared object-detection key so the two lists stay aligned for an image that has both objects and faces.
- `detectedfaceconfidence` — the same confidence under a face-only key, so you can search and facet on face confidence specifically.

Once per image it also writes:

- `facecount` — how many faces were found.

Like every treatment, it also writes `treatmenttype` identifying itself.

Alongside these searchable attributes, detection persists each face's geometry (its position in the image and facial landmark points) and assigns the face index. That geometry isn't a searchable attribute key — it's what powers the per-face crops and what [Face Recognition](treatment-face-recognition.md) later reads to identify each face. Note that detection assigns no identity: the `detectedface` key (a person's label) is written by recognition, not here.

## Cost and performance

Detection is a vision treatment and processes image data, so it's heavier than text treatments. Watch the source's treatment status counts to track progress through **INPROGRESS** to **DONE**.

## Configuration

Enable it on the data source and scan.

## Removing it

Removing the Face Detection treatment from a data source also removes the data it produced: the detected faces for that source's items are deleted along with the treatment. Because recognition depends on detection, removing detection also removes the basis for any recognition on that source.

## Relationship to other treatments

Face Detection is the **prerequisite for [Face Recognition](treatment-face-recognition.md)** and is pulled in automatically when recognition is enabled. It's independent of [Object Detection](treatment-object-detection.md) and the text treatments, and commonly runs alongside them on an image source.

---

**See also:** [Treatments overview](treatments.md) · [Face Recognition](treatment-face-recognition.md) · [Object Detection](treatment-object-detection.md) · [Search](search.md)

[← Back to documentation index](../README.md)
