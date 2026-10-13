package enterfind.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;

import enterfind.server.GraphQLTestClient;
import enterfind.server.dao.FaceGroupDao;
import enterfind.server.model.FaceEmbeddingMode;
import enterfind.server.model.FaceGroup;
import enterfind.server.scan.treatment.FaceEmbeddingModelService;

/**
 * The identity GraphQL surface over <b>real</b> recognised faces: the {@code Item.faces} field and
 * {@code renameFaceLabel}, both read back through the API a browser would use.
 *
 * <p>Separate from {@code FaceGraphQLIntegrationTest} on purpose. That file covers the model picker
 * and needs no faces, so it stays cheap; these two tests need the whole pipeline — scan, detect,
 * embed, group — and pay for it. Merging them would make all twelve picker tests download a model
 * and scan two photographs to assert things that never look at a face.
 *
 * <p>What only this level can catch is the pairing arriving <em>wrong over the wire</em>: the
 * {@code detectedface} attribute lists a photo's names alphabetically, so a client that zipped it
 * against the crops would caption the wrong person. {@code Item.faces} exists precisely so it does
 * not have to, and that is what these assertions pin.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FaceIdentityGraphQLIntegrationTest extends AbstractFaceTreatmentTest {

    private static final int EXPECTED_FACES_PER_IMAGE = 2;

    @LocalServerPort
    private int port;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private FaceGroupDao faceGroupDao;

    @Autowired
    private FaceEmbeddingModelService faceEmbeddingModelService;

    private GraphQLTestClient graphQLClient;

    @Override
    protected List<Treatments> treatmentsToEnable() {
        return List.of(Treatments.FACE_RECOGNITION);
    }

    @Override
    protected void configureInstanceBeforeTreatments() {
        faceEmbeddingModelService.setModel(FaceEmbeddingMode.BUILTIN, null, true);
    }

    @Test
    void itemFacesPairsEveryCropWithItsNameOverTheApi() {
        processAllTreatments(treatmentProcessor);
        graphQLClient = new GraphQLTestClient(port);

        JsonNode faces = queryFaces(TEST_IMAGE_1);

        assertThat(faces).hasSize(EXPECTED_FACES_PER_IMAGE);
        // Dense 0..N-1 in order: the crop URL addresses faces by index, so a hole or a reordering
        // would caption the wrong crop in the item view.
        assertThat(indexesOf(faces)).containsExactly(0, 1);
        assertThat(labelsOf(faces))
                .as("both faces are named, and the two people in one photo are named apart")
                .hasSize(EXPECTED_FACES_PER_IMAGE)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
    }

    @Test
    void renamingThroughTheApiIsVisibleOnTheNextItemFacesQuery() {
        processAllTreatments(treatmentProcessor);
        graphQLClient = new GraphQLTestClient(port);

        // Pick a group that actually appears in image 1, so the rename is observable there.
        String autoLabel = labelsOf(queryFaces(TEST_IMAGE_1)).getFirst();
        FaceGroup group = faceGroupDao.getFaceGroupByLabel(autoLabel).orElseThrow();

        JsonNode renamed = graphQLClient.executeAndGetData(String.format("""
                mutation { renameFaceLabel(oldLabel: "%s", newLabel: "Carol") }
                """, autoLabel));
        assertThat(renamed.path("renameFaceLabel").asBoolean()).isTrue();

        JsonNode faces = queryFaces(TEST_IMAGE_1);
        assertThat(labelsOf(faces))
                .as("the new name reaches the item view, the old one is gone")
                .contains("Carol")
                .doesNotContain(autoLabel);
        assertThat(indexesOf(faces))
                .as("a rename must not disturb which face is which")
                .containsExactly(0, 1);
        assertThat(faceGroupDao.getFaceGroupById(group.getId()).orElseThrow().getLabel())
                .isEqualTo("Carol");
    }

    private JsonNode queryFaces(String itemName) {
        return graphQLClient.executeAndGetData(String.format("""
                { search(name: "%s", skip: 0, howmany: 5) {
                    items { name faces { faceIndex label } } } }
                """, itemName))
                .path("search").path("items").get(0).path("faces");
    }

    private static List<Integer> indexesOf(JsonNode faces) {
        return faces.valueStream().map(face -> face.path("faceIndex").asInt()).toList();
    }

    private static List<String> labelsOf(JsonNode faces) {
        return faces.valueStream()
                .map(face -> face.path("label").isNull() ? null : face.path("label").asString())
                .toList();
    }
}
