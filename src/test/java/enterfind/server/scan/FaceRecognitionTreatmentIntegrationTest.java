package enterfind.server.scan;

import static enterfind.server.model.FieldNameConstants.FIELD_DETECTED_FACE;
import static enterfind.server.model.FieldNameConstants.FIELD_DETECTED_FACE_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_FACE_COUNT;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTED;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTION_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.dao.FaceGroupDao;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.FaceEmbeddingMode;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.ItemFace;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.treatment.FaceEmbeddingModelService;
import enterfind.server.scan.treatment.FaceEmbeddingUtils;
import enterfind.server.scan.treatment.FaceRecognitionTreatmentHandler;

/**
 * Integration tests for the FACE_RECOGNITION treatment against real photographs, under the built-in
 * EdgeFace-S model an operator would actually select.
 *
 * <p>This file owns identity only — groups, membership, similarity and labels. Detection stays
 * single-owner in {@code FaceDetectionTreatmentIntegrationTest}; asserting face counts here too
 * would leave neither file responsible for them.
 *
 * <p>The fixture's two photos contain the same two people, which is the whole point: a correct
 * grouping produces exactly two groups of two, pairing each person across both images. Getting that
 * shape by luck is hard — the same-photo faces must land in <em>different</em> groups while the
 * cross-photo faces land in the <em>same</em> one, so a model, an alignment or a threshold that is
 * subtly wrong fails rather than degrades.
 */
class FaceRecognitionTreatmentIntegrationTest extends AbstractFaceTreatmentTest {

    /** The two photos each show these two people, one face apiece. */
    private static final int EXPECTED_PEOPLE = 2;

    /**
     * Faces per photo. Numerically the same as {@link #EXPECTED_PEOPLE} only because each photo
     * shows each person exactly once; they are different claims and are kept apart so a fixture
     * change cannot silently conflate "how many people" with "how many faces in this image".
     */
    private static final int EXPECTED_FACES_PER_IMAGE = 2;

    /** Auto-assigned group labels, before any rename. */
    private static final String AUTO_LABEL_PATTERN = "Person \\d+";

    /** A human rename. Deliberately not "Person N"-shaped — that is what marks it worth carrying. */
    private static final String HUMAN_LABEL = "Alice";

    /** EdgeFace-S, the built-in. */
    private static final int BUILTIN_DIMENSION = 512;

    /** What the built-in's threshold derives to — the value the whole scale is calibrated around. */
    private static final double BUILTIN_DERIVED_THRESHOLD = 0.6;

    /**
     * SFace (OpenCV Zoo), the alternate model the change test switches to. CI-only and never
     * redistributed. Pinned by content hash so upstream drift fails loudly instead of silently
     * changing what the test proves.
     */
    private static final String ALTERNATE_MODEL_URL =
            "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/"
            + "face_recognition_sface_2021dec.onnx";
    private static final String ALTERNATE_MODEL_SHA256 =
            "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79";
    private static final int ALTERNATE_DIMENSION = 128;

    /**
     * The threshold SFace's own probe implies: {@code 0.572 × THRESHOLD_SCALE}, rounded.
     *
     * <p><b>This constant is where a product gap used to be.</b> Cosine scale is a property of the
     * model: EdgeFace separates our fixtures at same-person 0.635, SFace at 0.572 — both clear
     * {@code FaceModelProbe}'s {@code MIN_WITHIN_SIMILARITY} of 0.5, so the picker stores either. But
     * grouping once used a fixed 0.6, <em>above</em> SFace's same-person score, so selecting it split
     * every person in two and lost their human labels to a plurality tie — a model could pass
     * verification and still group wrongly, because the bar to be stored was looser than the bar to
     * be grouped. The picker now derives the threshold from the same probe measurement, so this test
     * needs no manual retune and asserts the derived value instead.
     */
    private static final double ALTERNATE_DERIVED_THRESHOLD = 0.54;

    /**
     * A threshold above what the built-in scores the same person at (0.635), so every face becomes
     * its own group. Chosen for that relationship, not as a round number.
     */
    private static final double SPLITTING_THRESHOLD = 0.9;

    /** Matches the bar {@code FaceModelProbe} holds a candidate model to before it can be stored. */
    private static final double MIN_WITHIN_SIMILARITY = 0.5;
    private static final double MAX_CROSS_SIMILARITY = 0.2;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private FaceGroupDao faceGroupDao;

    @Autowired
    private UserSettingsDao userSettingsDao;

    @Autowired
    private FaceEmbeddingModelService faceEmbeddingModelService;

    @Override
    protected List<Treatments> treatmentsToEnable() {
        // FACE_DETECTION is not listed: addTreatment auto-enables it, and naming it here would make
        // this test pass even if that auto-enable regressed.
        return List.of(Treatments.FACE_RECOGNITION);
    }

    @Override
    protected void configureInstanceBeforeTreatments() {
        // The operator's own path — no test-only setting writes. This fetches EdgeFace-S once per
        // machine and is served from the content-hash cache afterwards, exactly as the detection
        // test's YuNet download is.
        faceEmbeddingModelService.setModel(FaceEmbeddingMode.BUILTIN, null, true);
    }

    @Test
    void testFaceRecognitionGroupsTheSameTwoPeopleAcrossBothPhotos() {
        Item image1 = findItem(TEST_IMAGE_1);
        Item image2 = findItem(TEST_IMAGE_2);

        // Both halves of the pipeline: recognition only runs on what detection persisted, so this
        // test depends on the FACE_DETECTION rows too and holds both to their result contract below.
        DataSourceTreatment detectionTreatment =
                treatmentDao.getTreatment(testDataSource.id(), Treatments.FACE_DETECTION).orElseThrow();
        DataSourceTreatment recognitionTreatment =
                treatmentDao.getTreatment(testDataSource.id(), Treatments.FACE_RECOGNITION).orElseThrow();

        ItemTreatment detection1 =
                treatmentDao.getItemTreatment(detectionTreatment.getId(), image1.getId()).orElseThrow();
        ItemTreatment detection2 =
                treatmentDao.getItemTreatment(detectionTreatment.getId(), image2.getId()).orElseThrow();
        ItemTreatment itemTreatment1 =
                treatmentDao.getItemTreatment(recognitionTreatment.getId(), image1.getId()).orElseThrow();
        ItemTreatment itemTreatment2 =
                treatmentDao.getItemTreatment(recognitionTreatment.getId(), image2.getId()).orElseThrow();

        assertThat(processAllTreatments(treatmentProcessor))
                .as("detection and recognition should both drain")
                .isPositive();

        // --- Every one of the four item treatments completed ---
        for (ItemTreatment itemTreatment :
                List.of(detection1, detection2, itemTreatment1, itemTreatment2)) {
            assertThat(treatmentDao.getItemTreatmentById(itemTreatment.getId()).orElseThrow().getStatus())
                    .isEqualTo(ItemTreatmentStatus.DONE);
        }

        // --- What FACE_DETECTION handed recognition, asserted exhaustively ---
        // Asserted as this test's *inputs*, so a detection regression surfaces here as "detection
        // emitted the wrong thing" instead of as an inscrutable grouping failure three blocks down.
        // Detection's own correctness — dense face-index rows, bbox geometry, landmark persistence —
        // stays owned by FaceDetectionTreatmentIntegrationTest.
        for (ItemTreatment detection : List.of(detection1, detection2)) {
            Map<String, List<String>> results = resultsByKey(detection.getId());

            assertThat(results)
                    .as("FACE_DETECTION emits exactly these keys and no others")
                    .containsOnlyKeys(FIELD_TREATMENT_TYPE, FIELD_FACE_COUNT, FIELD_OBJECT_DETECTED,
                            FIELD_OBJECT_DETECTION_CONFIDENCE, FIELD_DETECTED_FACE_CONFIDENCE);

            assertThat(results.get(FIELD_TREATMENT_TYPE))
                    .containsExactly(Treatments.FACE_DETECTION.name());
            assertThat(results.get(FIELD_FACE_COUNT))
                    .containsExactly(String.valueOf(EXPECTED_FACES_PER_IMAGE));
            // objectdetected=face shares a key with object detection on purpose: COCO has no "face"
            // class, so detection contributes "face" to the unified "what's in this image" facet.
            assertThat(results.get(FIELD_OBJECT_DETECTED))
                    .containsOnly("face")
                    .hasSize(EXPECTED_FACES_PER_IMAGE);
            assertThat(results.get(FIELD_OBJECT_DETECTION_CONFIDENCE))
                    .hasSize(EXPECTED_FACES_PER_IMAGE)
                    .allSatisfy(FaceRecognitionTreatmentIntegrationTest::assertIsConfidence);
            assertThat(results.get(FIELD_DETECTED_FACE_CONFIDENCE))
                    .hasSize(EXPECTED_FACES_PER_IMAGE)
                    .allSatisfy(FaceRecognitionTreatmentIntegrationTest::assertIsConfidence);
        }

        // --- What FACE_RECOGNITION itself emitted, asserted exhaustively ---
        for (ItemTreatment itemTreatment : List.of(itemTreatment1, itemTreatment2)) {
            Map<String, List<String>> results = resultsByKey(itemTreatment.getId());

            assertThat(results)
                    .as("FACE_RECOGNITION emits exactly these keys and no others")
                    .containsOnlyKeys(FIELD_TREATMENT_TYPE, FIELD_DETECTED_FACE);

            assertThat(results.get(FIELD_TREATMENT_TYPE))
                    .containsExactly(Treatments.FACE_RECOGNITION.name());
            assertThat(results.get(FIELD_DETECTED_FACE))
                    .as("one label per recognised face, and the two people in a photo are named apart")
                    .hasSize(EXPECTED_FACES_PER_IMAGE)
                    .doesNotHaveDuplicates()
                    .allSatisfy(label -> assertThat(label).matches(AUTO_LABEL_PATTERN));
        }

        // Both photos show the same two people, so recognition named the same pair in each.
        assertThat(Set.copyOf(resultsByKey(itemTreatment2.getId()).get(FIELD_DETECTED_FACE)))
                .as("the same two names appear in both photos")
                .isEqualTo(Set.copyOf(resultsByKey(itemTreatment1.getId()).get(FIELD_DETECTED_FACE)));

        // --- Exactly one group per person, each holding that person's face from both photos ---
        List<FaceGroup> groups = faceGroupDao.getAllFaceGroups();
        assertThat(groups)
                .as("two people photographed twice should yield one group each, with no spurious third")
                .hasSize(EXPECTED_PEOPLE);
        assertThat(groups).allSatisfy(group ->
                assertThat(group.getFaceCount())
                        .as("group '%s' should hold one face from each photo", group.getLabel())
                        .isEqualTo(2));

        List<FaceEmbedding> embeddings1 = faceDao.getFaceEmbeddingsByItemTreatmentId(itemTreatment1.getId());
        List<FaceEmbedding> embeddings2 = faceDao.getFaceEmbeddingsByItemTreatmentId(itemTreatment2.getId());
        assertThat(embeddings1).hasSize(EXPECTED_PEOPLE);
        assertThat(embeddings2).hasSize(EXPECTED_PEOPLE);

        // The two faces in one photo are two different people, so they must not share a group. This
        // is what distinguishes a real grouping from one that lumped everything together and got the
        // group count right by accident.
        assertThat(groupIdsOf(embeddings1))
                .as("the two faces in %s are different people", TEST_IMAGE_1)
                .hasSize(EXPECTED_PEOPLE);
        assertThat(groupIdsOf(embeddings2))
                .as("the two faces in %s are different people", TEST_IMAGE_2)
                .hasSize(EXPECTED_PEOPLE);

        // ...and the same two people appear in both photos, so the group sets coincide.
        assertThat(groupIdsOf(embeddings2))
                .as("both photos show the same two people")
                .isEqualTo(groupIdsOf(embeddings1));

        // --- The embedding space itself separates, not merely the labels derived from it ---
        List<FaceEmbedding> allFaces = new ArrayList<>(embeddings1);
        allFaces.addAll(embeddings2);
        for (int i = 0; i < allFaces.size(); i++) {
            for (int j = i + 1; j < allFaces.size(); j++) {
                FaceEmbedding a = allFaces.get(i);
                FaceEmbedding b = allFaces.get(j);
                double similarity = FaceEmbeddingUtils.cosineSimilarity(
                        FaceEmbeddingUtils.fromBytes(a.getEmbedding()),
                        FaceEmbeddingUtils.fromBytes(b.getEmbedding()));
                if (a.getFaceGroupId().equals(b.getFaceGroupId())) {
                    assertThat(similarity)
                            .as("same-person similarity")
                            .isGreaterThan(MIN_WITHIN_SIMILARITY);
                } else {
                    assertThat(similarity)
                            .as("different-person similarity")
                            .isLessThan(MAX_CROSS_SIMILARITY);
                }
            }
        }

        // --- Every embedding is tagged with the model that produced it ---
        String modelHash = faceEmbeddingModelService.getModelHash().orElseThrow();
        assertThat(allFaces).allSatisfy(face -> assertThat(face.getModelHash()).isEqualTo(modelHash));

        // --- The item view sees the same pairing, resolved through the join it actually uses ---
        for (Item image : List.of(image1, image2)) {
            List<ItemFace> faces = faceDao.getFacesByItemId(image.getId());
            assertThat(faces).hasSize(EXPECTED_PEOPLE);
            assertThat(faces).extracting(ItemFace::faceIndex).containsExactly(0, 1);
            assertThat(faces).allSatisfy(face -> assertThat(face.label()).isNotNull());
        }
    }

    /**
     * Renaming a group has to reach the {@code detectedface} results too: they are a denormalized
     * copy of the label, and they — not FACE_GROUPS — are what search facets and the item view read.
     * A group renamed without them is a rename nobody can see.
     */
    @Test
    void testRenamingAGroupRewritesTheLabelEverywhereItWasCopied() {
        processAllTreatments(treatmentProcessor);

        FaceGroup group = faceGroupDao.getAllFaceGroups().getFirst();
        String autoLabel = group.getLabel();

        faceGroupDao.renameFaceLabel(autoLabel, HUMAN_LABEL);

        assertThat(faceGroupDao.getFaceGroupByLabel(autoLabel)).isEmpty();
        assertThat(faceGroupDao.getFaceGroupByLabel(HUMAN_LABEL))
                .get()
                .extracting(FaceGroup::getId)
                .isEqualTo(group.getId());

        // This person is in both photos, so each photo's results carry the new name exactly once.
        for (Item image : List.of(findItem(TEST_IMAGE_1), findItem(TEST_IMAGE_2))) {
            assertThat(resultsByKey(recognitionTreatmentFor(image).getId()).get(FIELD_DETECTED_FACE))
                    .as("the denormalized copy search reads")
                    .containsOnlyOnce(HUMAN_LABEL)
                    .doesNotContain(autoLabel);

            assertThat(faceDao.getFacesByItemId(image.getId()))
                    .as("the join the item view reads")
                    .extracting(ItemFace::label)
                    .containsOnlyOnce(HUMAN_LABEL)
                    .doesNotContain(autoLabel);
        }
    }

    /**
     * Disabling the model is the readiness gate seen from the other side: the groups go, and the
     * recognition rows — re-queued as UPDATED by the transition — must sit unclaimed rather than run
     * against no model. Detection is untouched and stays DONE, which is the point of the gate being
     * a claim-query condition and not a scan-time one.
     */
    /**
     * The quality gate's contract, end to end: a face below the confidence floor is still detected,
     * still stored, and still served as a crop — it simply never acquires an identity.
     *
     * <p>The floor is raised above the fixture faces (which detect at ~0.92) rather than finding a
     * genuinely marginal photo, because the assertion is about the gate's <em>effect</em>, not about
     * where the default sits. The default is calibrated on a 40k-photo corpus and is not something a
     * two-photo fixture could establish.
     */
    @Test
    void testFacesBelowTheConfidenceFloorKeepTheirCropsButGetNoIdentity() {
        userSettingsDao.setSetting(UserSettingName.FACE_RECOGNITION_MIN_CONFIDENCE, "0.99");
        try {
            assertThat(processAllTreatments(treatmentProcessor))
                    .as("both treatments still drain — gating is not failing")
                    .isPositive();

            Item image1 = findItem(TEST_IMAGE_1);
            ItemTreatment recognition = recognitionTreatmentFor(image1);

            assertThat(treatmentDao.getItemTreatmentById(recognition.getId()).orElseThrow().getStatus())
                    .as("a fully gated item completes, it does not fail")
                    .isEqualTo(ItemTreatmentStatus.DONE);

            // --- What the gate removed ---
            assertThat(faceDao.getFaceEmbeddingsByItemTreatmentId(recognition.getId()))
                    .as("no face cleared the floor, so none was embedded")
                    .isEmpty();
            assertThat(faceGroupDao.getAllFaceGroups())
                    .as("and no identity was invented for them")
                    .isEmpty();
            assertThat(resultsByKey(recognition.getId()))
                    .as("no detectedface labels either")
                    .doesNotContainKey(FIELD_DETECTED_FACE);

            // --- What the gate must NOT remove: detection is untouched ---
            assertThat(faceDao.getFaceDetectionsByItemId(image1.getId()))
                    .as("the detections themselves survive, so the crops still render")
                    .isNotEmpty();
            assertThat(faceDao.getFacesByItemId(image1.getId()))
                    .as("every face is still listed for the item view, just without a label")
                    .isNotEmpty()
                    .allSatisfy(face -> assertThat(face.label()).isNull());
        } finally {
            userSettingsDao.setSetting(UserSettingName.FACE_RECOGNITION_MIN_CONFIDENCE,
                    Float.toString(FaceRecognitionTreatmentHandler.DEFAULT_MIN_CONFIDENCE));
        }
    }

    @Test
    void testDisablingTheModelClearsGroupsAndLeavesRecognitionUnclaimable() {
        processAllTreatments(treatmentProcessor);
        assertThat(faceGroupDao.getAllFaceGroups()).isNotEmpty();
        assertThat(resultsByKey(recognitionTreatmentFor(findItem(TEST_IMAGE_1)).getId()))
                .containsKey(FIELD_DETECTED_FACE);

        faceEmbeddingModelService.setModel(FaceEmbeddingMode.NONE, null, false);

        assertThat(faceEmbeddingModelService.isReady()).isFalse();
        assertThat(faceEmbeddingModelService.getModelHash()).isEmpty();
        assertThat(faceGroupDao.getAllFaceGroups())
                .as("a disabled model leaves no identity behind")
                .isEmpty();

        assertThat(faceGroupDao.countPendingRecognition())
                .as("the transition re-queued the recognition rows")
                .isPositive();
        assertThat(processAllTreatments(treatmentProcessor))
                .as("...but the claim query must not hand them out with no model")
                .isZero();
        assertThat(faceGroupDao.countPendingRecognition())
                .as("still pending, not failed and not silently completed")
                .isPositive();

        // Detection is unaffected, and its faces now render as plain unlabeled crops.
        for (Item image : List.of(findItem(TEST_IMAGE_1), findItem(TEST_IMAGE_2))) {
            assertThat(treatmentDao.getItemTreatmentById(detectionTreatmentFor(image).getId())
                    .orElseThrow().getStatus()).isEqualTo(ItemTreatmentStatus.DONE);
            assertThat(faceDao.getFacesByItemId(image.getId()))
                    .hasSize(EXPECTED_FACES_PER_IMAGE)
                    .allSatisfy(face -> assertThat(face.label()).isNull());

            // The denormalized copy of the labels goes with the groups. A re-drain would normally
            // rewrite it per item, but there is no drain here — the rows would otherwise keep
            // captioning the item view and feeding the search facet with names nothing backs.
            assertThat(resultsByKey(recognitionTreatmentFor(image).getId()))
                    .as("no orphaned name survives the model being switched off")
                    .doesNotContainKey(FIELD_DETECTED_FACE);
        }
    }

    /**
     * Off and on again is reversible for the names. The picker offers <i>None</i> as a plain mode
     * among three, so an operator can reasonably switch it off and reconsider — and every human name
     * they ever assigned must not be the price. The snapshot is taken on the way out and must still
     * be there on the way back in, which is the part with a sharp edge: the return transition also
     * snapshots, and would clear the pending snapshot and replace it with nothing now that no group
     * carries a human name.
     *
     * <p>The same model is deliberately re-selected. Identity is then recomputed in the identical
     * space, so the clusters come back the same and the carryover is exact — this asserts the names
     * survive the round trip, where
     * {@link #testChangingToADifferentModelRegroupsInTheNewSpaceAndCarriesHumanLabelsBack} asserts
     * they survive a change of space.
     */
    @Test
    void testTurningTheModelOffAndBackOnRestoresTheHumanNames() {
        processAllTreatments(treatmentProcessor);

        FaceGroup named = faceGroupDao.getAllFaceGroups().getFirst();
        faceGroupDao.renameFaceLabel(named.getLabel(), HUMAN_LABEL);
        Set<Integer> namedFaceIndexesInImage1 = labeledFaceIndexes(findItem(TEST_IMAGE_1), HUMAN_LABEL);
        assertThat(namedFaceIndexesInImage1).hasSize(1);

        faceEmbeddingModelService.setModel(FaceEmbeddingMode.NONE, null, false);
        assertThat(faceGroupDao.snapshotCount())
                .as("switching off parks the human name rather than destroying it")
                .isEqualTo(2); // both of this person's faces, one per photo

        faceEmbeddingModelService.setModel(FaceEmbeddingMode.BUILTIN, null, true);
        assertThat(faceGroupDao.snapshotCount())
                .as("re-enabling must not overwrite the snapshot with the empty group table")
                .isEqualTo(2);

        assertThat(processAllTreatments(treatmentProcessor))
                .as("the rows parked as UPDATED become claimable again")
                .isPositive();

        assertThat(faceGroupDao.getAllFaceGroups()).hasSize(EXPECTED_PEOPLE);
        assertThat(faceGroupDao.getFaceGroupByLabel(HUMAN_LABEL))
                .as("the name came back")
                .isPresent();
        assertThat(labeledFaceIndexes(findItem(TEST_IMAGE_1), HUMAN_LABEL))
                .as("onto the same face it was given to")
                .isEqualTo(namedFaceIndexesInImage1);
        assertThat(resultsByKey(recognitionTreatmentFor(findItem(TEST_IMAGE_1)).getId())
                .get(FIELD_DETECTED_FACE))
                .as("and the denormalized copy is rebuilt with it")
                .containsOnlyOnce(HUMAN_LABEL);
        assertThat(faceGroupDao.snapshotCount())
                .as("the snapshot is dropped once the drain completes")
                .isZero();
    }

    /**
     * A model change end to end, into a <b>genuinely different embedding space</b>: snapshot the
     * human names, clear the groups, re-drive every recognition row, re-embed and re-cluster from
     * scratch under the new model, then carry the names back by membership overlap — with no rescan
     * anywhere.
     *
     * <p>The alternate is <b>SFace</b> from the OpenCV Zoo, and it is the right second model because
     * it differs from the built-in on every axis that matters: a different architecture and training
     * set (so the clusters are rebuilt, not reproduced), <b>128 dimensions</b> against EdgeFace's 512
     * (so "the dimension is read off the model and stored" is proven rather than asserted), and
     * <b>RGB_RAW</b> against EdgeFace's RGB_CENTERED (so the probe's preprocessing auto-detection is
     * exercised on a model whose convention we did not hardcode). A same-weights swap would prove the
     * plumbing but nothing about identity surviving a real change — re-embedding in the same space
     * reproduces the same clusters, making the overlap exact by construction.
     *
     * <p>SFace runs only here, is never redistributed, and needs only to be <em>different</em> rather
     * than accurate — which is what makes its weak licence standing acceptable in CI and nowhere else.
     */
    @Test
    void testChangingToADifferentModelRegroupsInTheNewSpaceAndCarriesHumanLabelsBack() {
        processAllTreatments(treatmentProcessor);

        FaceGroup named = faceGroupDao.getAllFaceGroups().getFirst();
        faceGroupDao.renameFaceLabel(named.getLabel(), HUMAN_LABEL);
        Set<Integer> namedFaceIndexesInImage1 = labeledFaceIndexes(findItem(TEST_IMAGE_1), HUMAN_LABEL);
        assertThat(namedFaceIndexesInImage1).hasSize(1);

        String builtinHash = faceEmbeddingModelService.getModelHash().orElseThrow();
        assertThat(embeddingDimensionsOf(findItem(TEST_IMAGE_1)))
                .as("the built-in is 512-dim")
                .containsOnly(BUILTIN_DIMENSION);

        // No manual retune: the picker derives SFace's threshold from its own probe. Grouping under
        // the built-in's 0.6 would split every person here — see ALTERNATE_DERIVED_THRESHOLD.
        faceEmbeddingModelService.setModel(FaceEmbeddingMode.CUSTOM, ALTERNATE_MODEL_URL, false);
        assertThat(faceEmbeddingModelService.getThreshold())
                .as("the new model brought its own threshold rather than inheriting the built-in's")
                .isEqualTo(ALTERNATE_DERIVED_THRESHOLD);

        // Pinned by content hash: an upstream file that changes or vanishes fails loudly here rather
        // than drifting into a test that quietly proves something else.
        String alternateHash = faceEmbeddingModelService.getModelHash().orElseThrow();
        assertThat(alternateHash)
                .as("SFace upstream bytes changed; re-verify the model before updating this pin")
                .isEqualTo(ALTERNATE_MODEL_SHA256);
        assertThat(alternateHash).isNotEqualTo(builtinHash);
        assertThat(faceEmbeddingModelService.getOutputDim())
                .as("read off the model at set time, not assumed to be 512")
                .isEqualTo(ALTERNATE_DIMENSION);

        assertThat(faceGroupDao.getAllFaceGroups())
                .as("the regroup clears the old groups before re-embedding")
                .isEmpty();
        assertThat(faceGroupDao.snapshotCount())
                .as("the human name was snapshotted, the auto ones were not")
                .isEqualTo(2); // both of this person's faces, one per photo

        assertThat(processAllTreatments(treatmentProcessor))
                .as("every recognition row re-drives without a rescan")
                .isPositive();

        // --- Nothing from the old space survived ---
        for (Item image : List.of(findItem(TEST_IMAGE_1), findItem(TEST_IMAGE_2))) {
            assertThat(embeddingDimensionsOf(image))
                    .as("every vector was recomputed in the new model's space")
                    .containsOnly(ALTERNATE_DIMENSION);
            assertThat(faceDao.getFaceEmbeddingsByItemTreatmentId(recognitionTreatmentFor(image).getId()))
                    .extracting(FaceEmbedding::getModelHash)
                    .as("no embedding still claims the previous model")
                    .containsOnly(alternateHash);
        }
        assertThat(faceGroupDao.getAllFaceGroups())
                .as("the same two people, re-clustered from scratch in the new space")
                .hasSize(EXPECTED_PEOPLE)
                .allSatisfy(group -> assertThat(group.getModelHash()).isEqualTo(alternateHash));
        assertThat(faceGroupDao.snapshotCount())
                .as("the snapshot is dropped once the drain completes")
                .isZero();

        // --- The human name tracked the real person across the change ---
        assertThat(faceGroupDao.getFaceGroupByLabel(HUMAN_LABEL))
                .as("carried over to the freshly built cluster")
                .isPresent();
        assertThat(labeledFaceIndexes(findItem(TEST_IMAGE_1), HUMAN_LABEL))
                .as("and onto the same face, not merely onto some cluster")
                .isEqualTo(namedFaceIndexesInImage1);
        assertThat(resultsByKey(recognitionTreatmentFor(findItem(TEST_IMAGE_1)).getId())
                .get(FIELD_DETECTED_FACE))
                .containsOnlyOnce(HUMAN_LABEL);
    }


    /**
     * A rescan of changed content must re-embed and <b>fresh-assign</b> from the new pixels, leaving
     * nothing of the old run behind.
     *
     * <p>This is the guard on the no-carry-forward decision. {@code face_index} is meaningful only
     * within one image's pixels, so an implementation that kept a face's old group id across a
     * content change would staple one person's identity onto whatever face happened to land at that
     * index next. The sharp case is a <em>shrinking</em> face set — here a two-face photograph is
     * replaced by a document scan — because that is what leaves a stale tail: delete-then-insert
     * drops it, append-and-overwrite does not.
     *
     * <p>It also pins the blast radius: rescanning one item must not disturb another item's
     * embeddings, which a content change has no reason to touch.
     */
    @Test
    void testRescanningChangedContentReEmbedsFreshAndLeavesNoStaleEmbedding() throws Exception {
        processAllTreatments(treatmentProcessor);

        Item image1 = findItem(TEST_IMAGE_1);
        Item image2 = findItem(TEST_IMAGE_2);
        List<String> originalIdsImage1 = embeddingIdsOf(image1);
        List<String> originalIdsImage2 = embeddingIdsOf(image2);
        assertThat(originalIdsImage1).hasSize(EXPECTED_FACES_PER_IMAGE);

        // Replace the photograph with a document scan: same item, entirely different pixels.
        Files.copy(tempTestDir.resolve("ocrable.jpg"), tempTestDir.resolve(TEST_IMAGE_1),
                StandardCopyOption.REPLACE_EXISTING);
        scanner.scan(testDataSource);
        assertThat(processAllTreatments(treatmentProcessor))
                .as("the changed item re-drives both of its face treatments")
                .isPositive();

        // --- Nothing of the previous run survives for the changed item ---
        Item rescanned = findItem(TEST_IMAGE_1);
        // The premise, asserted rather than assumed: the replacement is a document scan with no
        // faces at all, so the face set really did shrink (2 -> 0). If a fixture change ever made
        // this untrue the test would still pass on the checks below while proving nothing about a
        // stale tail, which is the whole point of it.
        assertThat(faceDao.getFaceDetectionsByItemId(rescanned.getId()))
                .as("the replacement image must have a different face set for this test to mean anything")
                .isEmpty();
        assertThat(embeddingIdsOf(rescanned))
                .as("every embedding is rebuilt from scratch; not one old row is reused")
                .doesNotContainAnyElementsOf(originalIdsImage1);
        assertThat(embeddingIdsOf(rescanned))
                .as("one embedding per detected face and no tail beyond it - the invariant a "
                        + "shrinking face set would break")
                .hasSameSizeAs(faceDao.getFaceDetectionsByItemId(rescanned.getId()));

        // --- The untouched item is genuinely untouched ---
        assertThat(embeddingIdsOf(findItem(TEST_IMAGE_2)))
                .as("a rescan of one item must not re-embed another")
                .containsExactlyElementsOf(originalIdsImage2);
    }

    /** Ids of one item's stored embeddings, in face-index order. */
    /**
     * Changing the threshold re-clusters what is already stored, and does so <b>without touching a
     * single vector</b> — that is the whole reason it is not a model change: the embeddings stay
     * valid, only the clustering they imply changes. The embedding row ids are asserted identical
     * before and after, which no amount of re-running the treatment could achieve (the save
     * delete-and-reinserts them with fresh ids).
     *
     * <p>Raising the threshold past what the model scores the same person at is the observable
     * effect: our two people, correctly paired across both photos at 0.6, must each split into two
     * one-face groups at 0.9 — four groups from the same four vectors.
     */
    @Test
    void testRaisingTheThresholdSplitsPeopleWithoutReEmbedding() {
        processAllTreatments(treatmentProcessor);
        assertThat(faceGroupDao.getAllFaceGroups()).hasSize(EXPECTED_PEOPLE);

        Item image1 = findItem(TEST_IMAGE_1);
        Item image2 = findItem(TEST_IMAGE_2);
        List<String> idsBefore = new ArrayList<>(embeddingIdsOf(image1));
        idsBefore.addAll(embeddingIdsOf(image2));

        faceEmbeddingModelService.setGroupingThreshold(SPLITTING_THRESHOLD);

        List<String> idsAfter = new ArrayList<>(embeddingIdsOf(image1));
        idsAfter.addAll(embeddingIdsOf(image2));
        assertThat(idsAfter)
                .as("no vector was rewritten — a recluster reassigns, it does not re-embed")
                .isEqualTo(idsBefore);

        assertThat(faceGroupDao.getAllFaceGroups())
                .as("above the model's same-person similarity, every face is its own person")
                .hasSize(EXPECTED_PEOPLE * EXPECTED_FACES_PER_IMAGE);
        assertThat(faceGroupDao.countPendingRecognition())
                .as("and nothing was queued for re-processing")
                .isZero();

        // The denormalized copy tracks the new labels, so the item view and the facet agree with the
        // groups rather than still naming clusters that no longer exist.
        for (Item image : List.of(image1, image2)) {
            List<String> labels = resultsByKey(recognitionTreatmentFor(image).getId()).get(FIELD_DETECTED_FACE);
            assertThat(labels).hasSize(EXPECTED_FACES_PER_IMAGE);
            assertThat(faceDao.getFacesByItemId(image.getId()))
                    .extracting(ItemFace::label)
                    .as("every face still carries the label of the group it is now in")
                    .containsExactlyInAnyOrderElementsOf(labels);
        }
    }

    /**
     * The round trip: split them at a high threshold, then put the original back and get the original
     * clustering — and the human name — again. Reclustering is a pure function of the stored vectors
     * and the threshold, so it has to be reversible; anything that carried state forward would show
     * up here as a name that failed to come back.
     */
    @Test
    void testLoweringTheThresholdBackRestoresTheGroupingAndTheName() {
        processAllTreatments(treatmentProcessor);
        FaceGroup named = faceGroupDao.getAllFaceGroups().getFirst();
        faceGroupDao.renameFaceLabel(named.getLabel(), HUMAN_LABEL);
        Set<Integer> namedFaceIndexesInImage1 = labeledFaceIndexes(findItem(TEST_IMAGE_1), HUMAN_LABEL);

        faceEmbeddingModelService.setGroupingThreshold(SPLITTING_THRESHOLD);
        faceEmbeddingModelService.setGroupingThreshold(BUILTIN_DERIVED_THRESHOLD);

        assertThat(faceGroupDao.getAllFaceGroups())
                .as("the same two people the built-in found the first time")
                .hasSize(EXPECTED_PEOPLE);
        assertThat(labeledFaceIndexes(findItem(TEST_IMAGE_1), HUMAN_LABEL))
                .as("carried through both regroups onto the same face")
                .isEqualTo(namedFaceIndexesInImage1);
        assertThat(faceGroupDao.snapshotCount())
                .as("a recluster finishes its own carryover — there is no drain to wait for")
                .isZero();
    }

    private List<String> embeddingIdsOf(Item image) {
        return faceDao.getFaceEmbeddingsByItemTreatmentId(recognitionTreatmentFor(image).getId()).stream()
                .map(FaceEmbedding::getId)
                .toList();
    }

    /** Vector lengths of one item's embeddings, as the stored bytes decode to. */
    private Set<Integer> embeddingDimensionsOf(Item image) {
        return faceDao.getFaceEmbeddingsByItemTreatmentId(recognitionTreatmentFor(image).getId()).stream()
                .map(embedding -> FaceEmbeddingUtils.fromBytes(embedding.getEmbedding()).length)
                .collect(Collectors.toSet());
    }

    /** The face indexes of one item carrying the given label, per the item-view join. */
    private Set<Integer> labeledFaceIndexes(Item image, String label) {
        return faceDao.getFacesByItemId(image.getId()).stream()
                .filter(face -> label.equals(face.label()))
                .map(ItemFace::faceIndex)
                .collect(Collectors.toSet());
    }

    private ItemTreatment recognitionTreatmentFor(Item image) {
        return itemTreatmentFor(image, Treatments.FACE_RECOGNITION);
    }

    private ItemTreatment detectionTreatmentFor(Item image) {
        return itemTreatmentFor(image, Treatments.FACE_DETECTION);
    }

    private ItemTreatment itemTreatmentFor(Item image, Treatments treatment) {
        DataSourceTreatment dataSourceTreatment =
                treatmentDao.getTreatment(testDataSource.id(), treatment).orElseThrow();
        return treatmentDao.getItemTreatment(dataSourceTreatment.getId(), image.getId()).orElseThrow();
    }

    private static Set<String> groupIdsOf(List<FaceEmbedding> embeddings) {
        return embeddings.stream().map(FaceEmbedding::getFaceGroupId).collect(Collectors.toSet());
    }

    /**
     * One item treatment's results as key -> values. Results are a multiset — several keys repeat
     * once per face — so grouping them is what lets the assertions above cover the whole set rather
     * than probing it key by key and never noticing an extra one.
     */
    private Map<String, List<String>> resultsByKey(String itemTreatmentId) {
        return treatmentDao.getItemTreatmentResults(itemTreatmentId).stream()
                .collect(Collectors.groupingBy(ItemTreatmentResult::getKey,
                        Collectors.mapping(ItemTreatmentResult::getValue, Collectors.toList())));
    }

    private static void assertIsConfidence(String value) {
        assertThat(value).isNotNull();
        assertThat(Double.parseDouble(value)).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
    }
}
