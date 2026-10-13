package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;

/**
 * Owns the probe fixtures: the four pre-aligned crops and the manifest {@link FaceModelProbe}
 * embeds. Detection runs <em>here</em>, once, so it never runs inside the set-model mutation.
 *
 * <p>Two halves, on purpose. {@link #regenerateProbeFixtures()} is {@code @Disabled} — it writes
 * into {@code src/main/resources} and is run by hand when the photos or the detector change. The
 * enabled test is the tripwire: nothing else would notice that a re-cropped photo has left the
 * checked-in crops describing pixels that no longer exist, and a stale fixture would fail every
 * operator's model with no hint why.
 */
class FaceProbeFixtureGeneratorTest {

    /** Where the generated fixtures live, relative to the project root. */
    private static final Path FIXTURE_OUTPUT_DIR = Path.of("src/main/resources", FaceModelProbe.FIXTURE_DIR);

    private static final String IMAGE_A = "IMG_20251019_185903.jpg";
    private static final String IMAGE_B = "IMG_20251023_194554.jpg";

    /**
     * The pinned ground truth, and the one thing detection cannot supply: which face is whose.
     * Both photos hold the same two people, and the same person sits on opposite sides in the two
     * shots. Keyed by source image, valued by person id in left-to-right bbox order.
     *
     * <p>Verify it by looking, not by reasoning: after regenerating, open the two crops that share a
     * person id and check they are the same face. That check is why the crops are checked in.
     */
    private static final Map<String, List<String>> PINNED_IDENTITIES = Map.of(
            IMAGE_A, List.of("A", "B"),
            IMAGE_B, List.of("B", "A"));

    private static final int FACES_PER_IMAGE = 2;

    /** Landmarks are pixel coordinates; a couple of pixels of detector drift is not staleness. */
    private static final float LANDMARK_TOLERANCE_PX = 2.0f;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @Disabled("Writes into src/main/resources. Run by hand when the photos or the detector change, "
            + "then eyeball the crops sharing a person id before committing.")
    void regenerateProbeFixtures() throws Exception {
        List<ProbeFaceFixture> fixtures = new ArrayList<>();
        Map<String, Integer> cropsPerPerson = new java.util.HashMap<>();
        Files.createDirectories(FIXTURE_OUTPUT_DIR);

        for (String imageName : List.of(IMAGE_A, IMAGE_B)) {
            BufferedImage source = readSourceImage(imageName);
            List<FaceDetectionResult> faces = detectTwoFacesLeftToRight(source, imageName);
            List<String> identities = PINNED_IDENTITIES.get(imageName);

            for (int i = 0; i < FACES_PER_IMAGE; i++) {
                FaceDetectionResult face = faces.get(i);
                String person = identities.get(i);
                int occurrence = cropsPerPerson.merge(person, 1, Integer::sum);
                String cropName = "person-" + person.toLowerCase() + "-" + occurrence + ".png";

                BufferedImage aligned = FaceAlignmentUtil.alignFace(source, face.landmarks());
                ImageIO.write(aligned, "png", FIXTURE_OUTPUT_DIR.resolve(cropName).toFile());

                fixtures.add(new ProbeFaceFixture(cropName, person, imageName, face.confidence(),
                        List.of(face.x(), face.y(), face.width(), face.height()),
                        toList(face.landmarks())));
            }
        }

        Files.writeString(FIXTURE_OUTPUT_DIR.resolve("probe-faces.json"),
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ProbeFaceFixture.Manifest(fixtures)));
    }

    /**
     * The checked-in fixtures still describe what the detector finds in the checked-in photos. Runs
     * detection precisely so the probe does not have to.
     */
    @Test
    void checkedInFixturesStillMatchTheDetectorAndThePhotos() throws Exception {
        ProbeFaceFixture.Manifest manifest;
        try (InputStream in = new ClassPathResource(FaceModelProbe.MANIFEST).getInputStream()) {
            manifest = objectMapper.readValue(in, ProbeFaceFixture.Manifest.class);
        }
        assertThat(manifest.faces())
                .as("two people, two crops each")
                .hasSize(4);
        assertThat(manifest.faces()).extracting(ProbeFaceFixture::person)
                .as("each person must appear twice, or within-person similarity has nothing to compare")
                .containsExactlyInAnyOrder("A", "A", "B", "B");

        for (String imageName : List.of(IMAGE_A, IMAGE_B)) {
            BufferedImage source = readSourceImage(imageName);
            List<FaceDetectionResult> detected = detectTwoFacesLeftToRight(source, imageName);
            List<ProbeFaceFixture> stored = manifest.faces().stream()
                    .filter(f -> f.sourceImage().equals(imageName))
                    .sorted(Comparator.comparingDouble(f -> f.bbox().get(0)))
                    .toList();

            assertThat(stored).as("fixtures for %s", imageName).hasSize(FACES_PER_IMAGE);
            for (int i = 0; i < FACES_PER_IMAGE; i++) {
                float[][] fresh = detected.get(i).landmarks();
                List<List<Float>> pinned = stored.get(i).landmarks();
                for (int p = 0; p < fresh.length; p++) {
                    assertThat(fresh[p][0])
                            .as("%s face %d landmark %d x — regenerate the probe fixtures",
                                    imageName, i, p)
                            .isCloseTo(pinned.get(p).get(0), org.assertj.core.data.Offset.offset(LANDMARK_TOLERANCE_PX));
                    assertThat(fresh[p][1])
                            .as("%s face %d landmark %d y — regenerate the probe fixtures",
                                    imageName, i, p)
                            .isCloseTo(pinned.get(p).get(1), org.assertj.core.data.Offset.offset(LANDMARK_TOLERANCE_PX));
                }
            }

            // The crops themselves must still be the shape the embedding models expect.
            for (ProbeFaceFixture fixture : stored) {
                try (InputStream in = new ClassPathResource(
                        FaceModelProbe.FIXTURE_DIR + "/" + fixture.crop()).getInputStream()) {
                    BufferedImage crop = ImageIO.read(in);
                    assertThat(crop).as("crop %s must be present and decodable", fixture.crop()).isNotNull();
                    assertThat(crop.getWidth()).isEqualTo(112);
                    assertThat(crop.getHeight()).isEqualTo(112);
                }
            }
        }
    }

    private static List<List<Float>> toList(float[][] landmarks) {
        List<List<Float>> out = new ArrayList<>(landmarks.length);
        for (float[] point : landmarks) {
            out.add(List.of(point[0], point[1]));
        }
        return out;
    }

    private static BufferedImage readSourceImage(String name) throws Exception {
        try (InputStream in = new ClassPathResource("face-test-images/" + name).getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IllegalStateException("Could not decode probe source image " + name);
            }
            return image;
        }
    }

    /** The two most confident faces, ordered left to right by bbox x — the order identities pin to. */
    @SuppressWarnings("unchecked")
    private static List<FaceDetectionResult> detectTwoFacesLeftToRight(BufferedImage source, String label)
            throws Exception {
        DjlBootstrap.ensureInitialized();
        Path modelDir = TreatmentUtils.ensureModelCachedByHash(
                FaceDetectionTreatmentHandler.DETECTION_MODEL_URL,
                FaceDetectionTreatmentHandler.DETECTION_MODEL_SHA256);
        Criteria<Image, List<FaceDetectionResult>> criteria = Criteria.builder()
                .setTypes(Image.class, (Class<List<FaceDetectionResult>>) (Class<?>) List.class)
                .optModelPath(modelDir)
                .optModelName(TreatmentUtils.HASH_CACHED_MODEL_NAME)
                .optEngine("OnnxRuntime")
                .optTranslator(new YuNetFaceDetectionTranslator())
                .build();

        List<FaceDetectionResult> faces;
        try (ZooModel<Image, List<FaceDetectionResult>> model = ModelZoo.loadModel(criteria);
             Predictor<Image, List<FaceDetectionResult>> predictor = model.newPredictor()) {
            faces = predictor.predict(ImageFactory.getInstance().fromImage(source));
        }
        assertThat(faces).as("faces detected in %s", label).hasSizeGreaterThanOrEqualTo(FACES_PER_IMAGE);

        List<FaceDetectionResult> topTwo = new ArrayList<>(faces);
        topTwo.sort(Comparator.comparingDouble(FaceDetectionResult::confidence).reversed());
        topTwo = new ArrayList<>(topTwo.subList(0, FACES_PER_IMAGE));
        topTwo.sort(Comparator.comparingDouble(FaceDetectionResult::x));
        return topTwo;
    }
}
