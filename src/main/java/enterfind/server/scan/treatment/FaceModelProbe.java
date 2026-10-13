package enterfind.server.scan.treatment;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;

/**
 * The set-model probe: proves a candidate embedding model produces a coherent embedding space
 * before it is ever stored, and detects its preprocessing convention along the way. Runs inside the
 * set-model mutation for {@code CUSTOM} models — a URL that fails it is never stored, which is what
 * makes "a stored model is a verified model" true.
 *
 * <p><b>Detection and alignment are precomputed.</b> The probe embeds four checked-in 112x112
 * ArcFace-aligned crops listed in {@code face-probe-faces/probe-faces.json}, generated once from
 * the full-size photos by {@code FaceProbeFixtureGeneratorTest}. Running YuNet here recovered
 * constants from two files that never change, at the cost of a second model load inside a
 * synchronous mutation and a failure mode where a detector hiccup — or simply no network to fetch
 * the detection model — reported the operator's model as bad. Detection correctness belongs to
 * {@code FaceDetectionTreatmentIntegrationTest}; this probe judges the embedding model.
 *
 * <p>The crops are the same ArcFace 5-point alignment production feeds the model, so the property
 * that made the live pipeline worth running survives: a model expecting some other alignment cannot
 * produce a coherent space from these crops by accident.
 *
 * <p><b>Preprocessing is detected, not asked.</b> The candidate is embedded under all four cells of
 * the {RGB, BGR} × {raw, centered} grid; the cell that best separates the two people wins and is
 * stored as the model's {@link FaceEmbeddingPreproc}. This is why the URL is the only operator input.
 */
@Component
public class FaceModelProbe {

    private static final Logger logger = LoggerFactory.getLogger(FaceModelProbe.class);

    static final String FIXTURE_DIR = "face-probe-faces";
    static final String MANIFEST = FIXTURE_DIR + "/probe-faces.json";

    // Separation bar, aligned with the grouping test (within > 0.5, across < 0.2). A model must clear
    // both to be stored; a garbage space fails one or the other.
    static final double MIN_WITHIN_SIMILARITY = 0.5;
    static final double MAX_CROSS_SIMILARITY = 0.2;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The outcome of a probe.
     *
     * @param passed             whether the model cleared the separation bar
     * @param preproc            the best-separating preprocessing (meaningful only when passed)
     * @param outputDim          the model's embedding dimension, read not assumed
     * @param withinSimilarity   the worst same-person similarity under the winning cell
     * @param crossSimilarity    the best (worst-case) cross-person similarity under the winning cell
     * @param message            human-readable summary, surfaced to the operator on failure
     */
    public record ProbeResult(boolean passed, FaceEmbeddingPreproc preproc, int outputDim,
                              double withinSimilarity, double crossSimilarity, String message) {}

    /**
     * Probe an already-fetched model directory ({@code model.onnx} inside it, per
     * {@link TreatmentUtils#fetchModelByContentHash}).
     */
    public ProbeResult probe(Path modelDir) {
        try {
            // person -> its aligned crops. Two people, two crops each; the check is that the pairs
            // within a person beat the pairs across people.
            Map<String, List<Image>> byPerson = loadFixtureCrops();
            if (byPerson.size() != 2) {
                throw new IllegalStateException(
                        "Probe fixtures must describe exactly 2 people, found " + byPerson.size());
            }

            FaceEmbeddingTranslator translator = new FaceEmbeddingTranslator(FaceEmbeddingPreproc.RGB_CENTERED);
            DjlBootstrap.ensureInitialized();
            Criteria<Image, float[]> criteria = Criteria.builder()
                    .setTypes(Image.class, float[].class)
                    .optModelPath(modelDir)
                    .optModelName(TreatmentUtils.HASH_CACHED_MODEL_NAME)
                    .optEngine("OnnxRuntime")
                    .optTranslator(translator)
                    .build();

            // Inference only: run the grid and collect vectors. Every judgement about them is made
            // by evaluate(), which is pure.
            Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> byCell = new LinkedHashMap<>();
            try (ZooModel<Image, float[]> model = ModelZoo.loadModel(criteria);
                 Predictor<Image, float[]> predictor = model.newPredictor()) {
                for (FaceEmbeddingPreproc preproc : FaceEmbeddingPreproc.values()) {
                    translator.setPreproc(preproc);
                    Map<String, List<float[]>> embeddings = new LinkedHashMap<>();
                    for (Map.Entry<String, List<Image>> entry : byPerson.entrySet()) {
                        List<float[]> vectors = new ArrayList<>();
                        for (Image crop : entry.getValue()) {
                            vectors.add(predictor.predict(crop));
                        }
                        embeddings.put(entry.getKey(), vectors);
                    }
                    byCell.put(preproc, embeddings);
                }
            }

            ProbeResult result = evaluate(byCell);
            logger.info("Face embedding probe {}: {}", result.passed() ? "passed" : "FAILED",
                    result.message());
            return result;

        } catch (Exception e) {
            // Shape rejection: a file that is not a 112x112x3 embedding ONNX throws on load or predict.
            logger.warn("Face embedding probe rejected the model: {}", e.getMessage());
            return new ProbeResult(false, null, 0, 0, 0,
                    "Model failed to load or run as a 112x112x3 face-embedding ONNX: " + e.getMessage());
        }
    }

    /**
     * The whole verdict, as a pure function of the vectors the candidate produced: pick the
     * best-separating preprocessing cell, read the dimension off it, and apply the bar.
     *
     * <p>Extracted from {@link #probe} deliberately. Reaching the rejection branches through a real
     * model means constructing an ONNX file that fails on purpose, and the branch that matters most —
     * <em>loads cleanly, produces a garbage space</em> — is exactly the one no honest model exhibits.
     * As a pure function each branch is reachable with fabricated vectors at exact boundary values,
     * which is both stronger and cheaper than a synthetic fixture, and it leaves the model estate
     * entirely real. Same reasoning that put grouping in {@code FaceGrouping.assign}.
     *
     * @param byCell every preprocessing cell's result: cell -> person -> that person's vectors
     */
    static ProbeResult evaluate(Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> byCell) {
        FaceEmbeddingPreproc bestPreproc = null;
        int bestDim = 0;
        double bestWithin = -1.0;
        double bestCross = 1.0;
        double bestScore = -Double.MAX_VALUE;

        for (Map.Entry<FaceEmbeddingPreproc, Map<String, List<float[]>>> cell : byCell.entrySet()) {
            Map<String, List<float[]>> embeddings = cell.getValue();
            if (embeddings.size() < 2 || hasDegenerate(embeddings)) {
                // A cell that produced NaN/all-zeros tells us nothing; another cell may still work.
                continue;
            }

            double within = worstWithinPerson(embeddings);
            double cross = bestAcrossPeople(embeddings);
            double score = within - cross;
            if (score > bestScore) {
                bestScore = score;
                bestPreproc = cell.getKey();
                bestWithin = within;
                bestCross = cross;
                bestDim = embeddings.values().iterator().next().get(0).length;
            }
        }

        if (bestPreproc == null) {
            return new ProbeResult(false, null, 0, 0, 0,
                    "Model produced only degenerate embeddings under every preprocessing convention");
        }

        boolean passed = bestWithin >= MIN_WITHIN_SIMILARITY && bestCross <= MAX_CROSS_SIMILARITY;
        String message = String.format(
                "best preprocessing %s: same-person similarity %.3f (need > %.2f), "
                        + "cross-person similarity %.3f (need < %.2f)",
                bestPreproc, bestWithin, MIN_WITHIN_SIMILARITY, bestCross, MAX_CROSS_SIMILARITY);
        return new ProbeResult(passed, bestPreproc, bestDim, bestWithin, bestCross, message);
    }

    private static boolean hasDegenerate(Map<String, List<float[]>> embeddings) {
        for (List<float[]> vectors : embeddings.values()) {
            if (vectors.size() < 2) {
                return true;
            }
            for (float[] vector : vectors) {
                if (FaceEmbeddingUtils.isDegenerate(vector)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The lowest same-person similarity: a model must clear the bar for both people, not on average. */
    private static double worstWithinPerson(Map<String, List<float[]>> embeddings) {
        double worst = 1.0;
        for (List<float[]> vectors : embeddings.values()) {
            for (int i = 0; i < vectors.size(); i++) {
                for (int j = i + 1; j < vectors.size(); j++) {
                    worst = Math.min(worst,
                            FaceEmbeddingUtils.cosineSimilarity(vectors.get(i), vectors.get(j)));
                }
            }
        }
        return worst;
    }

    /** The highest cross-person similarity: the worst case a grouping threshold would have to survive. */
    private static double bestAcrossPeople(Map<String, List<float[]>> embeddings) {
        List<List<float[]>> people = new ArrayList<>(embeddings.values());
        double best = -1.0;
        for (int p = 0; p < people.size(); p++) {
            for (int q = p + 1; q < people.size(); q++) {
                for (float[] a : people.get(p)) {
                    for (float[] b : people.get(q)) {
                        best = Math.max(best, FaceEmbeddingUtils.cosineSimilarity(a, b));
                    }
                }
            }
        }
        return best;
    }

    /** Read the manifest and its crops, grouped by the pinned person key, preserving file order. */
    private Map<String, List<Image>> loadFixtureCrops() throws Exception {
        ProbeFaceFixture.Manifest manifest;
        try (InputStream in = new ClassPathResource(MANIFEST).getInputStream()) {
            manifest = objectMapper.readValue(in, ProbeFaceFixture.Manifest.class);
        }

        Map<String, List<Image>> byPerson = new LinkedHashMap<>();
        for (ProbeFaceFixture face : manifest.faces()) {
            try (InputStream in = new ClassPathResource(FIXTURE_DIR + "/" + face.crop()).getInputStream()) {
                BufferedImage crop = ImageIO.read(in);
                if (crop == null) {
                    throw new IllegalStateException("Could not decode probe crop " + face.crop());
                }
                byPerson.computeIfAbsent(face.person(), k -> new ArrayList<>())
                        .add(ImageFactory.getInstance().fromImage(crop));
            }
        }
        return byPerson;
    }
}
