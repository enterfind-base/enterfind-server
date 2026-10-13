package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for Spring Boot Actuator endpoints.
 * Verifies that health and info endpoints are properly exposed and functional.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT
)
public class ActuatorIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ActuatorIntegrationTest.class);

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    public void testHealthEndpoint() throws Exception {
        // Test the health endpoint
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health", 
            String.class
        );
        
        // Verify response status
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        // Parse and verify JSON structure
        JsonNode healthJson = objectMapper.readTree(response.getBody());
        assertThat(healthJson.has("status")).isTrue();
        
        // Health should be UP
        String status = healthJson.get("status").asString();
        assertThat(status).isEqualTo("UP");
        
        logger.info("Health Endpoint Response: {}", response.getBody());
    }

    @Test
    public void testInfoEndpoint() {
        // Test the info endpoint
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/info", 
            String.class
        );
        
        // Verify response status
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        logger.info("Info Endpoint Response: {}", response.getBody());
    }

    @Test
    public void testActuatorBaseEndpoint() throws Exception {
        // Test the actuator base endpoint to see available endpoints
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator", 
            String.class
        );
        
        // Verify response status
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        // Parse and verify JSON structure
        JsonNode actuatorJson = objectMapper.readTree(response.getBody());
        assertThat(actuatorJson.has("_links")).isTrue();
        
        // Verify health and info links are present
        JsonNode links = actuatorJson.get("_links");
        assertThat(links.has("health")).isTrue();
        assertThat(links.has("info")).isTrue();
        assertThat(links.has("self")).isTrue();
        
        logger.info("Actuator Base Endpoint Response: {}", response.getBody());
    }

    @Test
    public void testHealthEndpointStructure() throws Exception {
        // Test the health endpoint for detailed structure verification
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health", 
            String.class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode healthJson = objectMapper.readTree(response.getBody());
        
        // Verify required fields
        assertThat(healthJson.has("status")).isTrue();
        
        // Status should be UP for a healthy application
        String status = healthJson.get("status").asString();
        assertThat(status).isIn("UP", "DOWN");
        
        // If there are components, verify structure
        if (healthJson.has("components")) {
            JsonNode components = healthJson.get("components");
            assertThat(components).isNotNull();
        }
    }

    @Test
    public void testNonExistentActuatorEndpoint() {
        // Test that non-exposed endpoints return 404
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/nonexistent", 
            String.class
        );
        
        // Should return 404 for non-existent endpoint
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}