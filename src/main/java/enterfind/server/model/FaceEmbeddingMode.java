package enterfind.server.model;

/**
 * The instance-global choice of face-embedding model, stored in {@code SYSTEM_SETTINGS} under
 * {@code FaceEmbeddingMode}.
 *
 * <ul>
 *   <li>{@link #NONE} — the default, and it must stay the default. Grouping is disabled; detection
 *       still runs. Nothing encumbered is fetched until an operator explicitly selects otherwise —
 *       a picker defaulting to the built-in would download and run the encumbered weights before the
 *       operator knew what they had agreed to, which is the exposure the whole design exists to
 *       avoid.</li>
 *   <li>{@link #BUILTIN} — the EdgeFace-S model we ship a URL for, behind an explicit
 *       non-commercial affirmation collected per selection.</li>
 *   <li>{@link #CUSTOM} — a bring-your-own model at an operator-supplied URL, tested by the
 *       set-model probe rather than affirmed.</li>
 * </ul>
 *
 * <p>{@code embeddingModelReady()} is simply {@code mode != NONE}: a stored {@code CUSTOM} passed
 * the inline probe when it was set, and {@code BUILTIN} we ship, so mode alone settles usability.
 */
public enum FaceEmbeddingMode {
    NONE,
    BUILTIN,
    CUSTOM;

    public static FaceEmbeddingMode fromStored(String value) {
        if (value == null) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
