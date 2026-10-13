package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class AvailableTreatmentsIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Test
    public void testAvailableTreatments() throws Exception {
        GraphQLTestClient client = new GraphQLTestClient(port);
        
        String query = """
            query {
                availableTreatments {
                    name
                    label
                    description
                }
            }
        """;
        
        JsonNode response = client.executeAndParse(query);
        JsonNode treatments = response.get("data").get("availableTreatments");
        
        assertNotNull(treatments);
        assertTrue(treatments.isArray());
        assertTrue(treatments.size() > 0);
        
        // Check that each treatment has required fields
        JsonNode firstTreatment = treatments.get(0);
        assertNotNull(firstTreatment.get("name"));
        assertNotNull(firstTreatment.get("label"));
        
        // Check that we have expected treatments
        boolean hasContent = false;
        boolean hasFaceDetection = false;
        
        for (JsonNode treatment : treatments) {
            String name = treatment.get("name").asString();
            if ("CONTENT".equals(name)) {
                assertEquals("Content Indexing", treatment.get("label").asString());
                hasContent = true;
            } else if ("FACE_DETECTION".equals(name)) {
                assertEquals("Face Detection", treatment.get("label").asString());
                hasFaceDetection = true;
            }
        }
        
        assertTrue(hasContent, "Should include CONTENT treatment");
        assertTrue(hasFaceDetection, "Should include FACE_DETECTION treatment");
    }
}
