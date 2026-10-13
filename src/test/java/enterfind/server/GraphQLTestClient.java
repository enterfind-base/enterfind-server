package enterfind.server;

import java.util.Map;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Utility class for executing GraphQL queries and mutations in integration tests.
 * Consolidates GraphQL client usage to provide a consistent API across all tests.
 */
public class GraphQLTestClient {

    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final int port;
    private String authToken;

    /**
     * Create a GraphQLTestClient using a new TestRestTemplate instance.
     *
     * @param port the server port to connect to
     */
    public GraphQLTestClient(int port) {
        this(new TestRestTemplate(), new JsonMapper(), port);
    }

    /**
     * Create a GraphQLTestClient with a provided TestRestTemplate.
     * Use this constructor when you have an autowired TestRestTemplate.
     *
     * @param restTemplate the TestRestTemplate to use
     * @param port the server port to connect to
     */
    public GraphQLTestClient(TestRestTemplate restTemplate, int port) {
        this(restTemplate, new JsonMapper(), port);
    }

    /**
     * Create a GraphQLTestClient with a provided TestRestTemplate and ObjectMapper.
     *
     * @param restTemplate the TestRestTemplate to use
     * @param objectMapper the ObjectMapper to use for JSON serialization
     * @param port the server port to connect to
     */
    public GraphQLTestClient(TestRestTemplate restTemplate, ObjectMapper objectMapper, int port) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.port = port;
    }

    /**
     * Set the authentication token to be used for subsequent requests.
     *
     * @param token the Bearer token (without "Bearer " prefix)
     */
    public void setAuthToken(String token) {
        this.authToken = token;
    }

    /**
     * Clear the authentication token.
     */
    public void clearAuthToken() {
        this.authToken = null;
    }

    /**
     * Get the current authentication token.
     *
     * @return the current token or null if not set
     */
    public String getAuthToken() {
        return authToken;
    }

    /**
     * Execute a GraphQL query or mutation and return the raw HTTP response.
     * Note: Authentication token should be passed as a parameter in the GraphQL query itself,
     * not as an HTTP header. This method is deprecated for authenticated requests.
     * Use executeWithToken() or include token in your query string directly.
     *
     * @param query the GraphQL query or mutation string
     * @return the ResponseEntity containing the response body
     */
    public ResponseEntity<String> execute(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = Map.of("query", query);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // When using autowired TestRestTemplate (port=0), use relative URL
        // When using manual TestRestTemplate with port, use absolute URL
        if (port == 0) {
            return restTemplate.postForEntity("/graphql", request, String.class);
        } else {
            return restTemplate.postForEntity("http://localhost:" + port + "/graphql", request, String.class);
        }
    }

    /**
     * Execute a GraphQL query or mutation with authentication token passed as a GraphQL variable.
     * The query must have a $token variable defined.
     *
     * @param query the GraphQL query or mutation string with $token variable
     * @param token the authentication token value
     * @return the ResponseEntity containing the response body
     */
    public ResponseEntity<String> executeWithToken(String query, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> variables = Map.of("token", token);
        Map<String, Object> requestBody = Map.of(
            "query", query,
            "variables", variables
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // When using autowired TestRestTemplate (port=0), use relative URL
        // When using manual TestRestTemplate with port, use absolute URL
        if (port == 0) {
            return restTemplate.postForEntity("/graphql", request, String.class);
        } else {
            return restTemplate.postForEntity("http://localhost:" + port + "/graphql", request, String.class);
        }
    }

    /**
     * Execute a GraphQL query or mutation and return the parsed JSON response.
     *
     * @param query the GraphQL query or mutation string
     * @return the parsed JsonNode response
     * @throws GraphQLTestException if the response cannot be parsed
     */
    public JsonNode executeAndParse(String query) {
        ResponseEntity<String> response = execute(query);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new GraphQLTestException("Failed to parse GraphQL response", e);
        }
    }

    /**
     * Execute a GraphQL query or mutation, asserting a successful HTTP response.
     *
     * @param query the GraphQL query or mutation string
     * @return the ResponseEntity containing the response body
     * @throws GraphQLTestException if the HTTP response is not successful
     */
    public ResponseEntity<String> executeSuccessfully(String query) {
        ResponseEntity<String> response = execute(query);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new GraphQLTestException("GraphQL request failed with status: " + response.getStatusCode() 
                + ", body: " + response.getBody());
        }
        return response;
    }

    /**
     * Execute a GraphQL mutation, asserting success and checking for GraphQL errors.
     *
     * @param mutation the GraphQL mutation string
     * @return the response body as a string
     * @throws GraphQLTestException if the response contains GraphQL errors
     */
    public String executeMutation(String mutation) {
        ResponseEntity<String> response = executeSuccessfully(mutation);
        JsonNode result = parseResponse(response);
        if (result.has("errors")) {
            throw new GraphQLTestException("GraphQL error: " + result.path("errors").toString());
        }
        return response.getBody();
    }

    /**
     * Execute a GraphQL query or mutation and extract the data portion.
     *
     * @param query the GraphQL query or mutation string
     * @return the "data" portion of the GraphQL response
     * @throws GraphQLTestException if the response cannot be parsed or has errors
     */
    public JsonNode executeAndGetData(String query) {
        JsonNode response = executeAndParse(query);
        if (response.has("errors") && !response.path("errors").isEmpty()) {
            throw new GraphQLTestException("GraphQL error: " + response.path("errors").toString());
        }
        return response.path("data");
    }

    /**
     * Parse an HTTP response body into a JsonNode.
     *
     * @param response the HTTP response
     * @return the parsed JsonNode
     * @throws GraphQLTestException if parsing fails
     */
    public JsonNode parseResponse(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new GraphQLTestException("Failed to parse GraphQL response", e);
        }
    }

    /**
     * Get the port number this client is configured to use.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Exception thrown when a GraphQL test operation fails.
     */
    public static class GraphQLTestException extends RuntimeException {
        public GraphQLTestException(String message) {
            super(message);
        }

        public GraphQLTestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
