package enterfind.server.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import enterfind.server.mcp.McpGraphQlContext;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironmentImpl;

/**
 * Fills the session token from the GraphQL context into the {@code token} argument of every field
 * that declares one, so a query sent through an MCP connector needs no {@code token} anywhere.
 *
 * <p>Only the MCP tool puts a token into the context, and only when the connector's Authorization
 * header was exchanged for a session; everything else passes through untouched. When it is present
 * it wins over a {@code token} the model supplied. No auth decision is made here: the resolvers
 * check the token exactly as they do for one passed by hand.
 *
 * <p>This relies on every argument named {@code token} being a session token, which is why
 * {@code userSetup} takes {@code setupToken} instead.
 */
@Component
public class ContextTokenInstrumentation implements Instrumentation {

    static final String TOKEN_ARGUMENT = "token";

    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
            InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
        String token = parameters.getExecutionContext().getGraphQLContext().get(McpGraphQlContext.SESSION_TOKEN);
        if (token == null
                || parameters.getExecutionStepInfo().getFieldDefinition().getArgument(TOKEN_ARGUMENT) == null) {
            return dataFetcher;
        }
        return environment -> {
            Map<String, Object> arguments = new LinkedHashMap<>(environment.getArguments());
            arguments.put(TOKEN_ARGUMENT, token);
            return dataFetcher.get(DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                .arguments(arguments)
                .build());
        };
    }
}
