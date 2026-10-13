package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.FaceGroupDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Item;
import enterfind.server.scan.treatment.FaceDetectionTreatmentHandler;

/**
 * The face-recognition GraphQL surface: the {@code faceEmbeddingModel} query, the
 * {@code setFaceEmbeddingModel} and {@code renameFaceLabel} mutations, the readiness guard on
 * enabling FACE_RECOGNITION, and the lazy {@code Item.faces} field.
 *
 * <p>Deliberately one level out from {@code FaceRecognitionTreatmentIntegrationTest}: this file
 * runs no models over photographs and asserts nothing about grouping. What it covers is what only
 * the API layer can get wrong — argument parsing, the error path leaving state untouched, and
 * whether a field is wired at all.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FaceGraphQLIntegrationTest extends BaseDatabaseTest {

    private static final String DATA_SOURCE_NAME = "face-api-source";

    @LocalServerPort
    private int port;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private FaceGroupDao faceGroupDao;

    private GraphQLTestClient graphQLClient;
    private LocalFileSystemDataSource dataSource;

    @BeforeEach
    void setUpFaceApiFixture() {
        super.setUp();
        graphQLClient = new GraphQLTestClient(port);
        dataSource = (LocalFileSystemDataSource) dataSourceDao.create(new LocalFileSystemDataSource(
                null, DATA_SOURCE_NAME, "0 0 * * *", LocalDateTime.now(), null,
                DataSourceStatus.IDLE, "/tmp/face-api-test", null));
    }

    // ==================== faceEmbeddingModel / setFaceEmbeddingModel ====================

    @Test
    void modelStatusDefaultsToNoneOnAFreshInstance() {
        JsonNode status = queryModelStatus();

        assertThat(status.path("mode").asString()).isEqualTo("NONE");
        assertThat(status.path("url").asString()).isEmpty();
        assertThat(status.path("outputDim").asInt()).isZero();
        assertThat(status.path("enabledDatasourceCount").asInt()).isZero();
    }

    @Test
    void selectingTheBuiltinWithoutTheNonCommercialAffirmationIsRejectedAndFetchesNothing() {
        JsonNode errors = setModelExpectingError("BUILTIN", null, false);

        assertThat(errors.toString()).containsIgnoringCase("non-commercial");
        assertThat(queryModelStatus().path("mode").asString())
                .as("the rejected selection never took effect")
                .isEqualTo("NONE");
    }

    @Test
    void selectingTheBuiltinWithTheAffirmationStoresItWithItsMeasuredDimension() {
        JsonNode status = setModelSuccessfully("BUILTIN", null, true);

        assertThat(status.path("mode").asString()).isEqualTo("BUILTIN");
        assertThat(status.path("outputDim").asInt())
                .as("EdgeFace-S is 512-dim")
                .isEqualTo(512);
        assertThat(status.path("url").asString())
                .as("the built-in URL is ours, not the operator's, so it is not echoed back")
                .isEmpty();
    }

    @Test
    void anUnknownModeIsRejected() {
        JsonNode errors = setModelExpectingError("SOMETHING_ELSE", null, true);

        assertThat(errors.toString()).containsIgnoringCase("Unknown embedding mode");
        assertThat(queryModelStatus().path("mode").asString()).isEqualTo("NONE");
    }

    @Test
    void aCustomUrlIsRequired() {
        JsonNode errors = setModelExpectingError("CUSTOM", "   ", false);

        assertThat(errors.toString()).containsIgnoringCase("model URL is required");
        assertThat(queryModelStatus().path("mode").asString()).isEqualTo("NONE");
    }

    /**
     * The realistic operator error: pasting a URL that is a perfectly good ONNX model but not a face
     * embedding model. It is the YuNet detector we already ship, so this needs no fixture — and the
     * assertion that matters is not the error but that the <em>previous</em> selection survives it.
     */
    @Test
    void aCustomModelThatFailsVerificationIsNeverStoredAndLeavesTheSelectionIntact() {
        setModelSuccessfully("BUILTIN", null, true);

        JsonNode errors = setModelExpectingError(
                "CUSTOM", FaceDetectionTreatmentHandler.DETECTION_MODEL_URL, false);
        assertThat(errors.toString()).containsIgnoringCase("verification failed");

        JsonNode status = queryModelStatus();
        assertThat(status.path("mode").asString())
                .as("a failed probe must not disturb the model already in force")
                .isEqualTo("BUILTIN");
        assertThat(status.path("outputDim").asInt()).isEqualTo(512);
    }

    // ==================== The readiness guard on enabling recognition ====================

    @Test
    void faceRecognitionCannotBeEnabledUntilAModelIsSelected() {
        JsonNode errors = addTreatmentExpectingError("FACE_RECOGNITION");
        assertThat(errors.toString()).containsIgnoringCase("requires an embedding model");

        assertThat(treatmentCount("FACE_RECOGNITION"))
                .as("no unclaimable treatment row is created, so there is no stalled counter to explain")
                .isZero();
    }

    @Test
    void faceRecognitionCanBeEnabledOnceAModelIsSelectedAndPullsDetectionInWithIt() {
        setModelSuccessfully("BUILTIN", null, true);

        JsonNode added = graphQLClient.executeAndGetData(addTreatmentMutation("FACE_RECOGNITION"))
                .path("addDataSourceTreatment");
        assertThat(added.path("treatment").asString()).isEqualTo("FACE_RECOGNITION");

        assertThat(treatmentCount("FACE_RECOGNITION")).isEqualTo(1);
        assertThat(treatmentCount("FACE_DETECTION"))
                .as("recognition consumes detection, so enabling it enables detection too")
                .isEqualTo(1);

        assertThat(queryModelStatus().path("enabledDatasourceCount").asInt())
                .as("the blast radius the settings card reports before a model change")
                .isEqualTo(1);
    }

    // ==================== renameFaceLabel ====================

    @Test
    void renameFaceLabelRenamesTheGroupThroughTheApi() {
        faceGroupDao.createFaceGroup("Person 42", new byte[] {0, 0, 0, 0}, "model-hash");

        JsonNode data = graphQLClient.executeAndGetData("""
                mutation { renameFaceLabel(oldLabel: "Person 42", newLabel: "Bob") }
                """);

        assertThat(data.path("renameFaceLabel").asBoolean()).isTrue();
        assertThat(faceGroupDao.getFaceGroupByLabel("Bob")).isPresent();
        assertThat(faceGroupDao.getFaceGroupByLabel("Person 42")).isEmpty();
    }

    /**
     * Renaming onto an existing label <b>merges</b> — it is not an error. One person occupies
     * several clusters when their appearance drifts between photographs (most obviously across an
     * age gap), and since no group id is exposed by the API, sharing a label is the only way to say
     * "these clusters are one person". This test previously asserted the opposite; the rejection was
     * removed deliberately, so restoring it would break merging.
     */
    @Test
    void renamingToALabelAlreadyInUseMergesTheTwoGroups() {
        faceGroupDao.createFaceGroup("Person 1", new byte[] {0, 0, 0, 0}, "model-hash");
        faceGroupDao.createFaceGroup("Bob", new byte[] {0, 0, 0, 0}, "model-hash");

        JsonNode response = graphQLClient.executeAndParse("""
                mutation { renameFaceLabel(oldLabel: "Person 1", newLabel: "Bob") }
                """);

        assertThat(response.path("errors")).as("merging is a legitimate operation").isEmpty();
        assertThat(faceGroupDao.getFaceGroupByLabel("Person 1"))
                .as("the old label is gone — its group now answers to Bob")
                .isEmpty();
        assertThat(faceGroupDao.getAllFaceGroups())
                .as("both groups survive as clusters; only the label they share has changed")
                .hasSize(2)
                .allSatisfy(group -> assertThat(group.getLabel()).isEqualTo("Bob"));
    }

    /**
     * Renaming a label that already names several groups moves all of them: "Bob is actually Robert"
     * has to carry every one of his clusters, or the rename would silently split him.
     */
    @Test
    void renamingADuplicatedLabelMovesEveryGroupCarryingIt() {
        faceGroupDao.createFaceGroup("Person 1", new byte[] {0, 0, 0, 0}, "model-hash");
        faceGroupDao.createFaceGroup("Bob", new byte[] {0, 0, 0, 0}, "model-hash");
        faceGroupDao.createFaceGroup("Person 2", new byte[] {0, 0, 0, 0}, "model-hash");

        // Two merges, so "Bob" names three clusters, then one rename of the merged identity.
        graphQLClient.executeAndGetData("""
                mutation { renameFaceLabel(oldLabel: "Person 1", newLabel: "Bob") }
                """);
        graphQLClient.executeAndGetData("""
                mutation { renameFaceLabel(oldLabel: "Person 2", newLabel: "Bob") }
                """);
        graphQLClient.executeAndGetData("""
                mutation { renameFaceLabel(oldLabel: "Bob", newLabel: "Robert") }
                """);

        assertThat(faceGroupDao.getFaceGroupByLabel("Bob")).isEmpty();
        assertThat(faceGroupDao.getAllFaceGroups())
                .hasSize(3)
                .allSatisfy(group -> assertThat(group.getLabel()).isEqualTo("Robert"));
    }

    @Test
    void renamingAnUnknownLabelIsRejected() {
        JsonNode response = graphQLClient.executeAndParse("""
                mutation { renameFaceLabel(oldLabel: "Nobody", newLabel: "Bob") }
                """);

        assertThat(response.path("errors")).isNotEmpty();
        assertThat(faceGroupDao.getFaceGroupByLabel("Bob")).isEmpty();
    }

    // ==================== Item.faces ====================

    /**
     * The wiring test for the lazy field. Whether the join <em>pairs</em> correctly is
     * {@code FaceDaoTest}'s and the recognition integration test's business; what only this level can
     * catch is the field not resolving at all — a schema-to-resolver mismatch, which would surface
     * here as a GraphQL error rather than an empty list.
     */
    @Test
    void itemFacesIsSelectableAndEmptyForAnItemWithNoDetectedFaces() {
        Item item = new Item();
        item.setDatasourceId(dataSource.id());
        item.setName("no-faces.jpg");
        item.setPath(null);
        item.setSizeBytes(1024L);
        item.setMimeType("image/jpeg");
        item.setTimeCreated(LocalDateTime.now());
        item.setTimeModified(LocalDateTime.now());
        itemDao.createItem(item);

        JsonNode data = graphQLClient.executeAndGetData("""
                { search(name: "no-faces.jpg", skip: 0, howmany: 1) {
                    totalHits items { name faces { faceIndex label } } } }
                """);

        JsonNode items = data.path("search").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("faces").isArray()).isTrue();
        assertThat(items.get(0).path("faces")).isEmpty();
    }

    // ==================== Helpers ====================

    private JsonNode queryModelStatus() {
        return graphQLClient.executeAndGetData(
                "{ faceEmbeddingModel { mode url outputDim enabledDatasourceCount } }")
                .path("faceEmbeddingModel");
    }

    private JsonNode setModelSuccessfully(String mode, String url, boolean nonCommercialAck) {
        return graphQLClient.executeAndGetData(setModelMutation(mode, url, nonCommercialAck))
                .path("setFaceEmbeddingModel");
    }

    /** Run a set-model mutation expected to fail, returning the {@code errors} node. */
    private JsonNode setModelExpectingError(String mode, String url, boolean nonCommercialAck) {
        JsonNode response = graphQLClient.executeAndParse(setModelMutation(mode, url, nonCommercialAck));
        assertThat(response.path("errors")).as("expected the mutation to be rejected").isNotEmpty();
        return response.path("errors");
    }

    private static String setModelMutation(String mode, String url, boolean nonCommercialAck) {
        String urlArgument = url == null ? "" : String.format(", url: \"%s\"", url);
        return String.format("""
                mutation { setFaceEmbeddingModel(mode: "%s"%s, nonCommercialAck: %s) {
                    mode url outputDim enabledDatasourceCount } }
                """, mode, urlArgument, nonCommercialAck);
    }

    private String addTreatmentMutation(String treatment) {
        return String.format("""
                mutation { addDataSourceTreatment(input: {
                    dataSourceName: "%s", dataSourceType: LOCAL_FILESYSTEM, treatment: %s
                }) { treatment } }
                """, DATA_SOURCE_NAME, treatment);
    }

    private JsonNode addTreatmentExpectingError(String treatment) {
        JsonNode response = graphQLClient.executeAndParse(addTreatmentMutation(treatment));
        assertThat(response.path("errors")).as("expected the mutation to be rejected").isNotEmpty();
        return response.path("errors");
    }

    private int treatmentCount(String treatment) {
        Integer count = dbAccess.queryForObject("""
                SELECT count(*) FROM DATASOURCE_TREATMENTS dt
                JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id
                WHERE ds.name = ? AND dt.treatment = ?
                """, Integer.class, DATA_SOURCE_NAME, treatment);
        return count == null ? 0 : count;
    }
}
