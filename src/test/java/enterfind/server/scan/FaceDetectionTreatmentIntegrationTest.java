package enterfind.server.scan;

import static enterfind.server.model.FieldNameConstants.FIELD_DETECTED_FACE_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_FACE_COUNT;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTED;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTION_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.treatment.YuNetFaceDetectionTranslator;

/**
 * Integration tests for the FACE_DETECTION treatment against real photographs.
 *
 * <p>This file owns detection's <em>correctness</em>: dense face-index rows, bbox geometry, landmark
 * persistence and the exact face counts. {@code FaceRecognitionTreatmentIntegrationTest}
 * also asserts detection's result keys and counts, deliberately — it consumes them, so asserting its
 * own inputs makes a detection regression read as such there instead of as a mysterious grouping
 * failure. That overlap is the result contract only; nothing above it is duplicated.
 */
class FaceDetectionTreatmentIntegrationTest extends AbstractFaceTreatmentTest {

    /**
     * Faces visible in each photo. Asserted exactly, not as {@code >= 2}, and that is deliberate:
     * under a {@code >=} assertion a YuNet regression that finds a spurious third face would pass
     * here and surface only later, as a grouping failure in the recognition tests.
     */
    private static final int EXPECTED_FACES_PER_IMAGE = 2;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private UserSettingsDao userSettingsDao;

    @Override
    protected List<Treatments> treatmentsToEnable() {
        return List.of(Treatments.FACE_DETECTION);
    }

    @Test
    void testFaceDetectionDetectsFacesInImages() {
        Item image1 = findItem(TEST_IMAGE_1);
        Item image2 = findItem(TEST_IMAGE_2);

        DataSourceTreatment dsTreatment =
            treatmentDao.getTreatment(testDataSource.id(), Treatments.FACE_DETECTION).orElse(null);
        assertNotNull(dsTreatment, "FACE_DETECTION treatment should exist");

        ItemTreatment it1 = treatmentDao.getItemTreatment(dsTreatment.getId(), image1.getId()).orElse(null);
        ItemTreatment it2 = treatmentDao.getItemTreatment(dsTreatment.getId(), image2.getId()).orElse(null);
        assertNotNull(it1, "Item treatment should exist for image1");
        assertNotNull(it2, "Item treatment should exist for image2");

        assertTrue(processAllTreatments(treatmentProcessor) > 0, "Should have processed at least one treatment");

        for (var entry : List.of(
                new Object[]{it1, image1, TEST_IMAGE_1},
                new Object[]{it2, image2, TEST_IMAGE_2})) {
            ItemTreatment it = (ItemTreatment) entry[0];
            Item image = (Item) entry[1];
            String imageName = (String) entry[2];

            ItemTreatment updated = treatmentDao.getItemTreatmentById(it.getId()).orElse(null);
            assertNotNull(updated, "Updated treatment should exist for " + imageName);
            assertEquals(ItemTreatmentStatus.DONE, updated.getStatus(), "Treatment should be DONE for " + imageName);

            List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(it.getId());
            assertNotNull(results, "Should have results for " + imageName);

            boolean hasTreatmentType = results.stream()
                .anyMatch(r -> FIELD_TREATMENT_TYPE.equals(r.getKey()) && "FACE_DETECTION".equals(r.getValue()));
            assertTrue(hasTreatmentType, "Should have treatmenttype=FACE_DETECTION for " + imageName);

            // objectdetected=face shares a key with object detection on purpose: COCO has no "face"
            // class, so this adds "face" to the unified "what's in this image" facet.
            long faceResults = results.stream()
                .filter(r -> FIELD_OBJECT_DETECTED.equals(r.getKey()) && "face".equals(r.getValue()))
                .count();
            long faceConfidenceCount = results.stream()
                .filter(r -> FIELD_DETECTED_FACE_CONFIDENCE.equals(r.getKey()))
                .count();
            assertEquals(EXPECTED_FACES_PER_IMAGE, faceResults,
                "Should emit exactly " + EXPECTED_FACES_PER_IMAGE + " objectdetected=face results for " + imageName);
            assertEquals(faceResults, faceConfidenceCount,
                "Face and detectedfaceconfidence counts should match for " + imageName);

            // Every objectdetected entry needs a matching objectdetectionconfidence entry, because
            // face detection and object detection share the objectdetected key. When they did not
            // match, an image containing both showed three things detected but one confidence, and
            // the item view read as though confidences had gone missing.
            long objectConfidenceCount = results.stream()
                .filter(r -> FIELD_OBJECT_DETECTION_CONFIDENCE.equals(r.getKey()))
                .count();
            assertEquals(faceResults, objectConfidenceCount,
                "objectdetected and objectdetectionconfidence counts must match for " + imageName);

            String faceCount = results.stream()
                .filter(r -> FIELD_FACE_COUNT.equals(r.getKey()))
                .map(ItemTreatmentResult::getValue)
                .findFirst()
                .orElse(null);
            assertEquals(String.valueOf(EXPECTED_FACES_PER_IMAGE), faceCount,
                "facecount should be " + EXPECTED_FACES_PER_IMAGE + " for " + imageName);

            results.stream()
                .filter(r -> FIELD_DETECTED_FACE_CONFIDENCE.equals(r.getKey()))
                .forEach(r -> {
                    assertNotNull(r.getValue(), "Confidence value should not be null");
                    double confidence = Double.parseDouble(r.getValue());
                    assertTrue(confidence > 0 && confidence <= 1.0,
                        "Confidence should be between 0 and 1, got " + confidence);
                });

            // === Persisted detection rows ===
            // Read one face at a time through the same lookup the crop endpoint uses. The crop URL
            // addresses faces by index, so the contract is a dense 0..N-1 range with nothing past
            // it: a gap 404s the thumbnail, an extra row means detection found a spurious face.
            List<FaceDetection> detections = new ArrayList<>();
            for (int i = 0; i < EXPECTED_FACES_PER_IMAGE; i++) {
                int faceIndex = i;
                detections.add(faceDao.getFaceDetectionByItemAndIndex(image.getId(), faceIndex)
                    .orElseThrow(() -> new AssertionError(
                        "Missing FACE_DETECTIONS row at index " + faceIndex + " for " + imageName)));
            }
            assertTrue(faceDao.getFaceDetectionByItemAndIndex(
                    image.getId(), EXPECTED_FACES_PER_IMAGE).isEmpty(),
                "Should persist exactly " + EXPECTED_FACES_PER_IMAGE
                    + " FACE_DETECTIONS rows for " + imageName);

            for (int i = 0; i < detections.size(); i++) {
                FaceDetection det = detections.get(i);

                assertTrue(det.getBboxX() >= 0 && det.getBboxX() <= 1, "bbox_x should be normalised");
                assertTrue(det.getBboxY() >= 0 && det.getBboxY() <= 1, "bbox_y should be normalised");
                assertTrue(det.getBboxW() > 0 && det.getBboxW() <= 1, "bbox_w should be a normalised positive width");
                assertTrue(det.getBboxH() > 0 && det.getBboxH() <= 1, "bbox_h should be a normalised positive height");

                assertNotNull(det.getLandmarks(),
                    "Landmarks must be persisted even though detection never reads them - "
                        + "FACE_RECOGNITION re-aligns from them instead of re-detecting the whole corpus");
                assertEquals(5, det.getLandmarks().length, "Should persist 5 landmarks");

                assertLandmarkOrdering(det.getLandmarks(), imageName + " face " + i);
            }
        }
    }

    /**
     * The guard face recognition silently depends on.
     *
     * <p>{@code FaceDetectionResult} and {@code FaceAlignmentUtil.ARCFACE_TEMPLATE} fix the 5-point
     * order as left eye, right eye, nose, left mouth corner, right mouth corner — "left" meaning
     * image-left. YuNet's own documentation names its landmarks anatomically (the subject's right
     * eye first), so whether the raw {@code kps} tensor needs reordering is a real question, and
     * getting it wrong is <em>silent</em>: {@code umeyamaSimilarity} permits no reflection, so a
     * swapped correspondence fits a ~180°-off rotation and yields garbage-aligned crops while
     * detection still looks perfectly fine. Detection itself never reads landmarks, so without this
     * geometric check a reordering bug lies dormant until recognition, surfacing there as inexplicably
     * bad grouping.
     *
     * <p>The photos are upright portraits, so plain geometry suffices.
     */
    private static void assertLandmarkOrdering(float[][] landmarks, String what) {
        float leftEyeX = landmarks[0][0], leftEyeY = landmarks[0][1];
        float rightEyeX = landmarks[1][0], rightEyeY = landmarks[1][1];
        float noseY = landmarks[2][1];
        float leftMouthX = landmarks[3][0], leftMouthY = landmarks[3][1];
        float rightMouthX = landmarks[4][0], rightMouthY = landmarks[4][1];

        assertTrue(leftEyeX < rightEyeX,
            "Landmark 0 must be the image-left eye and 1 the image-right eye for " + what
                + ", got x=" + leftEyeX + " and " + rightEyeX);
        assertTrue(leftMouthX < rightMouthX,
            "Landmark 3 must be the image-left mouth corner and 4 the image-right for " + what
                + ", got x=" + leftMouthX + " and " + rightMouthX);

        float eyeY = (leftEyeY + rightEyeY) / 2;
        float mouthY = (leftMouthY + rightMouthY) / 2;
        assertTrue(eyeY < noseY && noseY < mouthY,
            "Landmark 2 must be the nose, below the eyes and above the mouth, for " + what
                + ", got eyeY=" + eyeY + " noseY=" + noseY + " mouthY=" + mouthY);
    }

    @Test
    void testFaceDetectionSkippedOnNonImageFile() {
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item textItem = items.stream()
            .filter(item -> item.getName().toLowerCase().endsWith(".txt"))
            .findFirst()
            .orElse(null);

        assertNotNull(textItem, "Should have at least one text file in test data");

        DataSourceTreatment dsTreatment =
            treatmentDao.getTreatment(testDataSource.id(), Treatments.FACE_DETECTION).orElse(null);
        assertNotNull(dsTreatment, "FACE_DETECTION treatment should exist");

        // canProcess() returns false for non-image files — no item_treatment row is created
        assertTrue(treatmentDao.getItemTreatment(dsTreatment.getId(), textItem.getId()).isEmpty(),
            "text file should have no FACE_DETECTION item_treatment row");
    }

    /**
     * Setting {@code FaceDetectionConfidenceThreshold} to 0.99 is above any realistic YuNet score
     * for the test photos, so every candidate is filtered and the treatment completes with zero
     * detections — proving the threshold is honoured at inference time.
     */
    
    @Test
    void testHighConfidenceThresholdSuppressesFaceDetection() {
        // The FACE_DETECTION handler bean registers a FACE_DETECTION_CONFIDENCE_THRESHOLD observer
        // against this same UserSettingsDao bean at construction, so setSetting propagates to it.
        userSettingsDao.setSetting(UserSettingName.FACE_DETECTION_CONFIDENCE_THRESHOLD, "0.99");
        try {
            Item image1 = findItem(TEST_IMAGE_1);
            DataSourceTreatment dsTreatment =
                treatmentDao.getTreatment(testDataSource.id(), Treatments.FACE_DETECTION).orElse(null);
            assertNotNull(dsTreatment, "FACE_DETECTION treatment should exist");

            ItemTreatment it1 = treatmentDao.getItemTreatment(dsTreatment.getId(), image1.getId()).orElse(null);
            assertNotNull(it1, "Item treatment should exist for image1");

            assertTrue(processAllTreatments(treatmentProcessor) > 0, "Should have processed at least one treatment");

            ItemTreatment updated = treatmentDao.getItemTreatmentById(it1.getId()).orElse(null);
            assertNotNull(updated, "Updated treatment should exist");
            assertEquals(ItemTreatmentStatus.DONE, updated.getStatus(), "Treatment should be DONE");

            List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(it1.getId());
            long faceResults = results.stream()
                .filter(r -> FIELD_OBJECT_DETECTED.equals(r.getKey()) && "face".equals(r.getValue()))
                .count();
            assertEquals(0, faceResults,
                "Confidence threshold of 0.99 should suppress all face detections in " + TEST_IMAGE_1);

            // treatmenttype is always written, even when no faces pass the threshold
            assertTrue(results.stream()
                .anyMatch(r -> FIELD_TREATMENT_TYPE.equals(r.getKey()) && "FACE_DETECTION".equals(r.getValue())),
                "treatmenttype result must be present even when no faces are detected");
        } finally {
            // Reset static translator threshold so subsequent tests in other classes (e.g.
            // FaceDetectionConcurrentCloseTest) are not affected by this test's static side-effect.
            // @DirtiesContext rebuilds the Spring context before @BeforeEach, so the new handler
            // constructor reads from the DB — the DB value must also be reset here.
            userSettingsDao.setSetting(UserSettingName.FACE_DETECTION_CONFIDENCE_THRESHOLD,
                Float.toString(YuNetFaceDetectionTranslator.DEFAULT_CONFIDENCE_THRESHOLD));
        }
    }
}
