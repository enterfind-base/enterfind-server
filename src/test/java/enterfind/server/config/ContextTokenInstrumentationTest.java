package enterfind.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import enterfind.server.mcp.McpGraphQlContext;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

class ContextTokenInstrumentationTest {

    /**
     * Every field whose {@code token} argument is a session token. The instrumentation overwrites
     * {@code token} on all of them, so a new argument by that name must be a session token too — if
     * it is not, give it another name, as {@code userSetup(setupToken)} has.
     */
    private static final Set<String> SESSION_TOKEN_FIELDS = Set.of(
        "Query.dataSources",
        "Query.dataSource",
        "Query.dataSourceStats",
        "Query.dataSourceTreatments",
        "Query.testDataSource",
        "Query.search",
        "Query.itemAttributes",
        "Query.itemAttributeKeys",
        "Query.availableItemAttributeKeys",
        "Query.getFacets",
        "Query.userSetting",
        "Query.userSettings",
        "Query.aiSettings",
        "Query.aiProvider",
        "Query.faceEmbeddingModel",
        "Query.currentUser",
        "Query.humanUsers",
        "Query.humanUser",
        "Query.apiUsers",
        "Query.apiUser",
        "Query.savedQueries",
        "Query.savedQuery",
        "Query.remoteAccessConfig",
        "Query.remoteUsage",
        "Query.remoteQuota",
        "Mutation.createLocalFileSystemDataSource",
        "Mutation.createRemoteFileSystemDataSource",
        "Mutation.createS3BucketDataSource",
        "Mutation.updateLocalFileSystemDataSource",
        "Mutation.updateRemoteFileSystemDataSource",
        "Mutation.updateS3BucketDataSource",
        "Mutation.deleteDataSource",
        "Mutation.clearDataSourceData",
        "Mutation.scanDataSource",
        "Mutation.addDataSourceTreatment",
        "Mutation.removeDataSourceTreatment",
        "Mutation.updateDataSourceTreatmentConfig",
        "Mutation.setUserSetting",
        "Mutation.setAiSetting",
        "Mutation.setFaceEmbeddingModel",
        "Mutation.setFaceGroupingThreshold",
        "Mutation.renameFaceLabel",
        "Mutation.testAiConnection",
        "Mutation.interpretCronExpression",
        "Mutation.explainCronExpression",
        "Mutation.interpretSkipPattern",
        "Mutation.explainSkipPattern",
        "Mutation.interpretSearchQuery",
        "Mutation.logout",
        "Mutation.createHumanUser",
        "Mutation.updateHumanUser",
        "Mutation.deleteHumanUser",
        "Mutation.regenerateTotpSecret",
        "Mutation.regenerateRecoveryCodes",
        "Mutation.regenerateSetupToken",
        "Mutation.createApiUser",
        "Mutation.deleteApiUser",
        "Mutation.createSavedQuery",
        "Mutation.deleteSavedQuery",
        "Mutation.setRemoteAccessEnabled",
        "Mutation.reenrollRemoteAccess",
        "Mutation.deleteItem",
        "Subscription.dataSourceStatsUpdates",
        "Subscription.remoteAccessConfigUpdates"
    );

    private static final String SDL = """
        type Query {
            echo(token: String, other: String): String
            noToken(other: String): String
            setup(setupToken: String): String
        }
        """;

    private final GraphQL graphQL = GraphQL.newGraphQL(stubSchema())
        .instrumentation(new ContextTokenInstrumentation())
        .build();

    @Test
    void fillsAnAbsentToken() {
        assertEquals("session", fetch("{ echo(other: \"x\") }", "session").get("echo"));
    }

    @Test
    void overridesAModelSuppliedToken() {
        assertEquals("session", fetch("{ echo(token: \"from-model\") }", "session").get("echo"));
    }

    @Test
    void keepsTheOtherArguments() {
        assertEquals("x", fetch("{ noToken(other: \"x\") }", "session").get("noToken"));
    }

    @Test
    void leavesFieldsWithoutATokenArgumentAlone() {
        assertEquals("one-time", fetch("{ setup(setupToken: \"one-time\") }", "session").get("setup"));
    }

    @Test
    void doesNothingWithoutAContextToken() {
        assertEquals("from-model", fetch("{ echo(token: \"from-model\") }", null).get("echo"));
    }

    @Test
    void everyTokenArgumentInTheSchemaIsASessionToken() throws IOException {
        TypeDefinitionRegistry registry = new SchemaParser().parse(readSchema());
        Set<String> withToken = new TreeSet<>();
        registry.getTypes(ObjectTypeDefinition.class).forEach(type -> addTokenFields(type, withToken));
        registry.objectTypeExtensions().values().forEach(list -> list.forEach(type -> addTokenFields(type, withToken)));

        assertEquals(new TreeSet<>(SESSION_TOKEN_FIELDS), withToken);
    }

    private static void addTokenFields(ObjectTypeDefinition type, Set<String> into) {
        type.getFieldDefinitions().stream()
            .filter(field -> field.getInputValueDefinitions().stream().anyMatch(arg -> arg.getName().equals("token")))
            .forEach(field -> into.add(type.getName() + "." + field.getName()));
    }

    private Map<String, Object> fetch(String query, String contextToken) {
        ExecutionInput.Builder input = ExecutionInput.newExecutionInput().query(query);
        if (contextToken != null) {
            input.graphQLContext(Map.of(McpGraphQlContext.SESSION_TOKEN, contextToken));
        }
        ExecutionResult result = graphQL.execute(input.build());
        assertEquals(0, result.getErrors().size(), () -> result.getErrors().toString());
        return result.getData();
    }

    private static GraphQLSchema stubSchema() {
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
            .type("Query", type -> type
                .dataFetcher("echo", env -> env.getArgument("token"))
                .dataFetcher("noToken", env -> env.getArgument("other"))
                .dataFetcher("setup", env -> env.getArgument("setupToken")))
            .build();
        return new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
    }

    private static String readSchema() throws IOException {
        try (InputStream in = ContextTokenInstrumentationTest.class.getResourceAsStream("/graphql/schema.graphqls")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
