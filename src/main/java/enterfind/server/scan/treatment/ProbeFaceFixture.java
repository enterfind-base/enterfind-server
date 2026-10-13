package enterfind.server.scan.treatment;

import java.util.List;

/**
 * One pre-aligned probe face: the 112x112 ArcFace-aligned crop the set-model probe embeds, plus the
 * provenance that lets a human verify it and a generator reproduce it.
 *
 * <p>These are checked in as PNGs beside a JSON manifest of these records, produced once by
 * {@code FaceProbeFixtureGeneratorTest} from the full-size photos under
 * {@code src/test/resources/face-test-images}. The probe therefore runs <b>no detection</b>: YuNet
 * on two files that never change was recovering constants, and it made the picker fail for reasons
 * that had nothing to do with the model under test — a detector build that found one face, or no
 * network to fetch the detection model at all.
 *
 * <p><b>{@code person} is the pinned ground truth</b>, and pinning it here is the point. It used to
 * be derived at probe time (top two by confidence, sorted by bbox x) with a comment telling the next
 * reader to go check it against the pixels. Now the four crops are visible artifacts in the repo:
 * open the two crops sharing a {@code person} and you can see they are the same face.
 *
 * @param crop        classpath-relative name of the aligned 112x112 PNG
 * @param person      identity key; two crops sharing it are the same person, and that is what the
 *                    probe's within-versus-across similarity check is built on
 * @param sourceImage the test-resources photo this was cut from
 * @param confidence  the detector's confidence at generation time
 * @param bbox        source-image bounding box [x, y, width, height], for human verification
 * @param landmarks   the 5 source-image landmark pairs the alignment used, so it can be redone
 */
public record ProbeFaceFixture(String crop, String person, String sourceImage, float confidence,
                               List<Float> bbox, List<List<Float>> landmarks) {

    /** The manifest file: every fixture the probe embeds. */
    public record Manifest(List<ProbeFaceFixture> faces) {}
}
