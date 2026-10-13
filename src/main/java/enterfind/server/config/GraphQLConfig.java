package enterfind.server.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.jspecify.annotations.NonNull;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.NotFoundException;
import enterfind.server.exception.SystemIssueException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.scalars.ExtendedScalars;
import graphql.schema.DataFetchingEnvironment;

/**
 * GraphQL configuration including exception handling
 */
@Configuration
public class GraphQLConfig {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLConfig.class);

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder.scalar(ExtendedScalars.GraphQLLong);
    }

    @Bean
    public WebGraphQlInterceptor errorLoggingInterceptor() {
        return (request, chain) -> chain.next(request).doOnNext(response -> {
            List<GraphQLError> errors = response.getExecutionResult().getErrors();
            if (!errors.isEmpty()) {
                logger.warn("GraphQL response contained {} error(s) for operation [{}]: {}",
                        errors.size(), request.getDocument(), errors);
            }
        });
    }

    @Bean
    public DataFetcherExceptionResolverAdapter exceptionResolver() {
        return new DataFetcherExceptionResolverAdapter() {
            @Override
            protected GraphQLError resolveToSingleError(@NonNull Throwable ex, @NonNull DataFetchingEnvironment env) {
                if (ex instanceof NotFoundException) {
                    Map<String, Object> extensions = new HashMap<>();
                    extensions.put("errorType", "NOT_FOUND");
                    extensions.put("errorCode", "RESOURCE_NOT_FOUND");
                    
                    return GraphqlErrorBuilder.newError(env)
                            .message(ex.getMessage())
                            .errorType(ErrorType.NOT_FOUND)
                            .extensions(extensions)
                            .build();
                } else if (ex instanceof InvalidArgumentException) {
                    Map<String, Object> extensions = new HashMap<>();
                    extensions.put("errorType", "INVALID_ARGUMENT");
                    extensions.put("errorCode", "BAD_REQUEST");
                    
                    return GraphqlErrorBuilder.newError(env)
                            .message(ex.getMessage())
                            .errorType(ErrorType.BAD_REQUEST)
                            .extensions(extensions)
                            .build();
                } else if (ex instanceof SystemIssueException) {
                    Map<String, Object> extensions = new HashMap<>();
                    extensions.put("errorType", "SYSTEM_ISSUE");
                    extensions.put("errorCode", "INTERNAL_ERROR");

                    return GraphqlErrorBuilder.newError(env)
                            .message(ex.getMessage())
                            .errorType(ErrorType.INTERNAL_ERROR)
                            .extensions(extensions)
                            .build();
                } else if (ex instanceof SecurityException) {
                    Map<String, Object> extensions = new HashMap<>();
                    String message = ex.getMessage();
                    
                    // Distinguish between authentication errors (not logged in / invalid credentials) and 
                    // authorization errors (logged in but insufficient permissions or disabled account)
                    boolean isAuthenticationError = message != null && 
                        (message.contains("Authentication required") || 
                         message.contains("Invalid") || 
                         message.contains("expired"));
                    
                    if (isAuthenticationError) {
                        extensions.put("errorType", "UNAUTHORIZED");
                        extensions.put("errorCode", "AUTHENTICATION_REQUIRED");
                        return GraphqlErrorBuilder.newError(env)
                                .message(message)
                                .errorType(ErrorType.UNAUTHORIZED)
                                .extensions(extensions)
                                .build();
                    } else {
                        // Authorization error - user is authenticated but lacks permission or account is disabled
                        extensions.put("errorType", "FORBIDDEN");
                        extensions.put("errorCode", "INSUFFICIENT_PERMISSIONS");
                        return GraphqlErrorBuilder.newError(env)
                                .message(message)
                                .errorType(ErrorType.FORBIDDEN)
                                .extensions(extensions)
                                .build();
                    }
                }
                return null;
            }
        };
    }
}