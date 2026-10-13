package enterfind.server.scan.treatment;

/**
 * The two silent preprocessing axes a face-embedding model can disagree on, detected by the
 * set-model probe rather than asked of the operator:
 *
 * <ul>
 *   <li><b>Channel order</b> — RGB (InsightFace, EdgeFace) vs BGR (AdaFace, and our own YuNet path).
 *       Neither is inspectable from the {@code .onnx}.</li>
 *   <li><b>Normalization</b> — whether {@code (px − 127.5) / 127.5} is applied in Java before the
 *       model ({@code centered}), or the graph bakes its own normalization and wants raw 0–255
 *       ({@code raw}).</li>
 * </ul>
 *
 * <p>The probe runs a four-cell grid over {RGB, BGR} × {raw, centered}, keeps whichever separates
 * the two bundled people best, and stores the winner as {@code FaceEmbeddingModelPreproc}. It is
 * part of the derived model identity, so the same file under a different channel order or
 * normalization is correctly a different model.
 *
 * <p>The exact divisor washes out: a global scale on the input becomes a global scale on the
 * (pre-L2) output, and the translator L2-normalizes, so {@code /127.5} and {@code /128} yield
 * identical unit vectors. Only the <em>offset</em> (centered vs not) and the <em>channel order</em>
 * actually change the embedding. The built-in EdgeFace-S is {@link #RGB_CENTERED}.
 */
public enum FaceEmbeddingPreproc {
    RGB_RAW(false, false),
    RGB_CENTERED(false, true),
    BGR_RAW(true, false),
    BGR_CENTERED(true, true);

    private final boolean bgr;
    private final boolean centered;

    FaceEmbeddingPreproc(boolean bgr, boolean centered) {
        this.bgr = bgr;
        this.centered = centered;
    }

    /** True if the model expects planes in B, G, R order rather than R, G, B. */
    public boolean isBgr() {
        return bgr;
    }

    /** True if {@code (px − 127.5) / 127.5} is applied in Java; false feeds raw 0–255. */
    public boolean isCentered() {
        return centered;
    }

    /** Parse a stored value, falling back to the built-in convention for anything unrecognized. */
    public static FaceEmbeddingPreproc fromStored(String value) {
        if (value == null) {
            return RGB_CENTERED;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return RGB_CENTERED;
        }
    }
}
