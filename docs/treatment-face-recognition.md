# Treatment: Face Recognition

Groups the faces that [Face Detection](treatment-face-detection.md) found and lets you label them, so you can search your images by person. Detection finds the faces; recognition works out which faces belong to the same person and lets you put a name to each group.

## What it does

Recognition turns each detected face into a numeric fingerprint (an embedding) and compares those fingerprints. Faces that are close enough are treated as the same person and grouped together. You can then give a group a name, and every face in it — across every photo — carries that label. Once labeled, a person becomes a search term: find all images of that person, and see which face in a photo is whom.

## Face Detection is enabled automatically

Recognition depends on the faces detection produces, so **enabling Face Recognition on a data source automatically requires and enables [Face Detection](treatment-face-detection.md)** on that source. You don't add detection separately — selecting recognition brings it along. (The reverse isn't true: you can run detection alone, without recognition, if you only want to find images that contain faces.)

## The embedding model

Recognition needs a face-embedding model to produce those fingerprints. The model is chosen **once for the whole instance** (not per data source), because every face in the instance has to be embedded by the same model for the comparisons to mean anything. There are three modes:

- **None** — no model selected; recognition is effectively off until you pick one.
- **Built-in** — Enterfind's bundled model. Because high-quality face-embedding models are almost always trained on research datasets that carry non-commercial restrictions, the built-in model is for **non-commercial use**, and selecting it requires acknowledging that. See *Licensing* below.
- **Custom** — a model you supply by URL. Enterfind fetches it, probes it to confirm it actually works (see below), and only adopts it if it passes; a model that fails the probe is never stored, and your previous selection stays in force.

Changing the instance's model is a significant action: it invalidates the embeddings already computed under the old model, because fingerprints from two different models aren't comparable. Enterfind shows how many data sources have face recognition enabled — the "blast radius" of a model change — so you can see what a switch affects before you make it.

### Why custom models are probed, not trusted

Face-embedding models share a common format but disagree on subtle preprocessing details (color-channel order, how pixel values are scaled). These details are often undocumented, and getting one wrong silently produces a model that "runs" but can't actually tell people apart. Rather than trust a flag, Enterfind **probes** a custom model on known same-person and different-person faces and keeps it only if it genuinely separates them. This is why a bad custom model is rejected at selection time instead of quietly degrading your results later.

## The grouping threshold

Two faces are judged the same person when their fingerprints are similar enough — and "enough" is a **threshold** you can tune. Enterfind derives a sensible starting threshold from the chosen model's probe, and you can adjust it:

- **Raise the threshold** to require closer matches — fewer faces per group, less chance of merging two different people, but more chance the same person is split across groups.
- **Lower the threshold** to be more permissive — larger groups, more chance of catching every photo of someone, but more risk of merging look-alikes.

Changing the threshold **re-clusters every stored face immediately** — it does not re-embed them. The fingerprints are unchanged; only the grouping of them changes. That makes threshold tuning fast and reversible: nudge it, see how the groups change, nudge again.

## Labeling people

When faces are grouped, you assign a name to a group. Renaming a label updates the group and every face result carrying it, so correcting or changing a name is a single action that propagates everywhere that face appears. Unlabeled faces (recognition off for that source, not yet processed, or simply skipped) show as plain crops with no name.

## Fields written

For each identified face, Face Recognition writes one [attribute key](item-attributes.md):

- `detectedface` — the group's label: the person's name once you've named the group, or the automatic group label (such as `Person 3`) until then. This is the [standard field](item-attributes.md#standard-fields) you search and facet on to find someone by name. Renaming a group rewrites every `detectedface` value carrying the old label, so the search term follows the name.

Like every treatment, it also writes `treatmenttype` identifying itself.

The fingerprints themselves and the group memberships are stored separately as recognition's working data, not as searchable attribute keys — `detectedface` is the one key that surfaces in search. Note that only faces confident enough to identify reliably get a `detectedface`; a face that was detected but too low-quality to embed stays an unlabeled crop, which is why an image can show more faces than it has `detectedface` values.

## Licensing

This is worth stating plainly. The models that make face recognition accurate are, in practice, trained on datasets released for **research / non-commercial** use, and that restriction attaches to the model weights regardless of what license a code repository or a redistributor stamps on them. Consequences:

- The **built-in** model is offered for non-commercial use, and choosing it requires you to acknowledge that.
- For **commercial** use, the realistic path is to bring a **custom** model you actually hold the rights to. One mainstream option is to purchase a commercial license for a reputable model pack (for example, InsightFace sells commercial licenses for its packs) and point Enterfind's custom-model setting at weights you're licensed to use.
- Treat a permissive-looking license on a downloaded weight file as a claim to check, not a fact — the license that governs the weights is the one the people who trained them granted, which is frequently more restrictive than the code repository's license suggests.

Enterfind gives you the mechanism and the honest warning; the licensing decision for the model you run is yours.

## Cost and performance

Recognition is a vision treatment: it embeds every detected face and clusters the results. Initial processing of a large photo archive takes time, visible in the source's treatment status counts. Threshold changes afterward are cheap (re-clustering only); model changes are expensive (everything must be re-embedded).

## Configuration summary

- Enable **Face Recognition** on the source — this automatically enables [Face Detection](treatment-face-detection.md) too — and scan.
- Select the instance's **embedding model** (built-in with the non-commercial acknowledgement, or a custom model by URL).
- Tune the **grouping threshold** to taste; re-clustering is immediate.
- **Label** groups with names; rename as needed.

## Removing it

Removing the Face Recognition treatment from a data source also removes the data it produced: the embeddings, groupings, and labels for that source's faces are deleted along with the treatment. (Detection's found-faces data is governed by the [Face Detection](treatment-face-detection.md) treatment separately.)

## Relationship to other treatments

Recognition sits directly on top of [Face Detection](treatment-face-detection.md) — which it enables automatically — and is meaningless without it. It's independent of [Object Detection](treatment-object-detection.md) and the text treatments, which can run on the same source to make images findable by their objects and any text or metadata they contain as well as by who's in them.

---

**See also:** [Treatments overview](treatments.md) · [Face Detection](treatment-face-detection.md) · [Object Detection](treatment-object-detection.md) · [Search](search.md)

[← Back to documentation index](../README.md)
