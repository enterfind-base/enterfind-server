#!/usr/bin/env node

/**
 * MCP GraphQL Wrapper - Stdio to GraphQL Bridge (Stateless)
 *
 * This script allows Claude Desktop (and other MCP clients) to connect directly
 * to an Enterfind GraphQL API, eliminating the need for a hosted MCP server.
 *
 * The wrapper is STATELESS - it does not store authentication tokens.
 * Instead, AI clients introspect the schema and discover that authenticated
 * operations require a `token: String` argument. After calling `login`,
 * the AI receives a token and is responsible for including it in subsequent calls.
 *
 * This makes auth explicit and discoverable via GraphQL introspection.
 *
 * The wrapper implements the MCP protocol locally, translating:
 *   - introspect_graphql_schema → GraphQL introspection query
 *   - execute_graphql → Direct GraphQL query execution (pure pass-through)
 *
 * Usage:
 *   node mcp-graphql-wrapper.js [graphql-url]
 *
 * Examples:
 *   node mcp-graphql-wrapper.js http://localhost:8080/graphql
 *   node mcp-graphql-wrapper.js https://your-enterfind-instance.example.com/graphql
 *
 * Claude Desktop Configuration (claude_desktop_config.json):
 * {
 *   "mcpServers": {
 *     "enterfind": {
 *       "command": "node",
 *       "args": ["/path/to/mcp-graphql-wrapper.js", "http://localhost:8080/graphql"]
 *     }
 *   }
 * }
 */

const readline = require('readline');
const https = require('https');
const http = require('http');
const { URL } = require('url');

// Get GraphQL URL from command line or use default
const GRAPHQL_URL = process.argv[2] || 'http://localhost:8080/graphql';

// Server info
const SERVER_NAME = 'enterfind-graphql-mcp';
const SERVER_VERSION = '2.0.0';
const PROTOCOL_VERSION = '2024-11-05';

// Parse the URL to determine http vs https
const graphqlUrl = new URL(GRAPHQL_URL);
const isHttps = graphqlUrl.protocol === 'https:';
const httpModule = isHttps ? https : http;

// Configure for self-signed certificates (optional - remove in production)
const agent = isHttps ? new https.Agent({
  rejectUnauthorized: false // Allow self-signed certificates
}) : undefined;

// ==================== Tool Definitions ====================

const TOOLS = [
  {
    name: 'introspect_graphql_schema',
    description: 'Get the GraphQL schema for Enterfind document search system. IMPORTANT: Call this FIRST before using execute_graphql. ' +
      'The schema reveals all available queries, mutations, and their arguments. ' +
      'AUTHENTICATION: Authenticated operations have a `token: String` argument. ' +
      'Call the `login` mutation first to get a token, then pass it to subsequent operations. ' +
      'Key operations: (1) \'isAuthenticationRequired\' - check if auth is needed, ' +
      '(2) \'login\' mutation - get auth token, ' +
      '(3) \'search\' query - find documents with faceted filtering, ' +
      '(4) \'availableItemAttributeKeys\' - discover facet dimensions, ' +
      '(5) \'getFacets\' - get facet values and counts, ' +
      '(6) \'itemAttributes\' - detailed item metadata.',
    inputSchema: {
      type: 'object',
      properties: {
        includeDescriptions: {
          type: 'boolean',
          description: 'Include field descriptions in schema output',
          default: true
        }
      }
    }
  },
  {
    name: 'execute_graphql',
    description: 'Execute GraphQL queries/mutations against Enterfind. This is a pure pass-through - no token injection. ' +
      'PREREQUISITE: Call introspect_graphql_schema first to understand the schema. ' +
      'AUTHENTICATION WORKFLOW: ' +
      '(1) Call isAuthenticationRequired query to check if login is needed. ' +
      '(2) If auth required, call login mutation with nickname and totpCode to get a token. ' +
      '(3) For authenticated operations, pass the token as a variable: { "token": "your-token-here", ... }. ' +
      'SEARCH WORKFLOW: ' +
      '(1) Start with a broad \'search\' query using searchString with keywords. ' +
      '(2) Call \'availableItemAttributeKeys\' to discover available facet dimensions. ' +
      '(3) Use \'getFacets\' with specific attribute keys to see value distributions. ' +
      '(4) Refine your search with searchConditions array to filter by facet values. ' +
      '(5) Use \'itemAttributes\' to get detailed metadata for specific items. ' +
      'DOCUMENT RETRIEVAL: Construct URLs using: /content/{dataSourceType}/{dataSourceName}/{folder}/{name}.',
    inputSchema: {
      type: 'object',
      properties: {
        query: {
          type: 'string',
          description: 'GraphQL query or mutation string'
        },
        variables: {
          type: 'object',
          description: 'Query variables as JSON object. Include "token" for authenticated operations.'
        }
      },
      required: ['query']
    }
  }
];

// ==================== GraphQL Introspection Query ====================

const INTROSPECTION_QUERY = `
query IntrospectionQuery {
  __schema {
    queryType { name }
    mutationType { name }
    types {
      name
      kind
      description
      fields(includeDeprecated: false) {
        name
        description
        args {
          name
          description
          type {
            name
            kind
            ofType {
              name
              kind
            }
          }
        }
        type {
          name
          kind
          ofType {
            name
            kind
          }
        }
      }
      inputFields {
        name
        description
        type {
          name
          kind
          ofType {
            name
            kind
          }
        }
      }
      enumValues {
        name
        description
      }
    }
  }
}
`;

// ==================== HTTP Client ====================

/**
 * Send a GraphQL request to the server (pure pass-through, no header injection)
 */
function sendGraphQLRequest(query, variables = null) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({
      query: query,
      variables: variables
    });

    const headers = {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
      'Accept': 'application/json'
    };

    const options = {
      hostname: graphqlUrl.hostname,
      port: graphqlUrl.port || (isHttps ? 443 : 80),
      path: graphqlUrl.pathname,
      method: 'POST',
      headers: headers,
      agent: agent
    };

    const req = httpModule.request(options, (res) => {
      let responseData = '';

      res.on('data', (chunk) => {
        responseData += chunk;
      });

      res.on('end', () => {
        try {
          const jsonResponse = JSON.parse(responseData);
          resolve(jsonResponse);
        } catch (error) {
          reject(new Error(`Failed to parse GraphQL response: ${error.message}`));
        }
      });
    });

    req.on('error', (error) => {
      reject(new Error(`GraphQL request failed: ${error.message}`));
    });

    req.write(body);
    req.end();
  });
}

// ==================== MCP Protocol Handlers ====================

/**
 * Handle initialize request
 */
function handleInitialize(request) {
  return {
    jsonrpc: '2.0',
    id: request.id,
    result: {
      protocolVersion: PROTOCOL_VERSION,
      serverInfo: {
        name: SERVER_NAME,
        version: SERVER_VERSION
      },
      capabilities: {
        tools: {}
      }
    }
  };
}

/**
 * Handle tools/list request
 */
function handleToolsList(request) {
  return {
    jsonrpc: '2.0',
    id: request.id,
    result: {
      tools: TOOLS
    }
  };
}

/**
 * Handle tools/call request
 */
async function handleToolsCall(request) {
  try {
    const { name: toolName, arguments: args } = request.params;

    if (toolName === 'introspect_graphql_schema') {
      return await executeIntrospection(request.id);
    } else if (toolName === 'execute_graphql') {
      return await executeQuery(request.id, args);
    } else {
      return errorResponse(request.id, -32602, `Unknown tool: ${toolName}`);
    }
  } catch (error) {
    return errorResponse(request.id, -32603, `Tool execution error: ${error.message}`);
  }
}

// ==================== GraphQL Tools ====================

/**
 * Execute GraphQL introspection
 */
async function executeIntrospection(requestId) {
  try {
    const result = await sendGraphQLRequest(INTROSPECTION_QUERY);

    if (result.errors && result.errors.length > 0) {
      return toolErrorResult(requestId, `GraphQL introspection failed: ${JSON.stringify(result.errors)}`);
    }

    return toolSuccessResult(requestId, JSON.stringify(result.data, null, 2));
  } catch (error) {
    return toolErrorResult(requestId, `Introspection error: ${error.message}`);
  }
}

/**
 * Execute GraphQL query (pure pass-through)
 */
async function executeQuery(requestId, args) {
  try {
    const { query, variables } = args;

    if (!query) {
      return toolErrorResult(requestId, 'Query parameter is required');
    }

    const result = await sendGraphQLRequest(query, variables);

    if (result.errors && result.errors.length > 0) {
      return toolErrorResult(requestId, `GraphQL query failed: ${JSON.stringify(result.errors)}`);
    }

    return toolSuccessResult(requestId, JSON.stringify(result.data, null, 2));
  } catch (error) {
    return toolErrorResult(requestId, `Query execution error: ${error.message}`);
  }
}

// ==================== Response Helpers ====================

/**
 * Create a successful tool result
 */
function toolSuccessResult(requestId, data) {
  return {
    jsonrpc: '2.0',
    id: requestId,
    result: {
      content: [{
        type: 'text',
        text: JSON.stringify({
          isError: false,
          result: typeof data === 'string' ? data : JSON.stringify(data, null, 2)
        }, null, 2)
      }],
      isError: false
    }
  };
}

/**
 * Create an error tool result
 */
function toolErrorResult(requestId, errorMessage) {
  return {
    jsonrpc: '2.0',
    id: requestId,
    result: {
      content: [{
        type: 'text',
        text: JSON.stringify({
          isError: true,
          errorMessage: errorMessage
        }, null, 2)
      }],
      isError: true
    }
  };
}

/**
 * Create error response
 */
function errorResponse(id, code, message) {
  return {
    jsonrpc: '2.0',
    id: id,
    error: {
      code: code,
      message: message
    }
  };
}

// ==================== Main Stdio Loop ====================

/**
 * Process a JSON-RPC request
 */
async function processRequest(request) {
  switch (request.method) {
    case 'initialize':
      return handleInitialize(request);
    case 'notifications/initialized':
      // This is a notification, no response needed
      return null;
    case 'tools/list':
      return handleToolsList(request);
    case 'tools/call':
      return await handleToolsCall(request);
    default:
      // Unknown method - return error unless it's a notification
      if (request.id !== undefined && request.id !== null) {
        return errorResponse(request.id, -32601, `Method not found: ${request.method}`);
      }
      return null;
  }
}

/**
 * Main stdio loop
 */
async function main() {
  // Log to stderr (not stdout, which is used for MCP communication)
  console.error(`MCP GraphQL Wrapper v${SERVER_VERSION} (Stateless)`);
  console.error(`GraphQL URL: ${GRAPHQL_URL}`);
  console.error(`Protocol: ${isHttps ? 'HTTPS' : 'HTTP'}`);
  console.error(`Authentication: Pass token as GraphQL variable (introspect schema to see)`);
  console.error(`Waiting for JSON-RPC requests on stdin...`);

  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
  });

  // Track pending requests
  let pendingRequests = 0;
  let isClosing = false;

  rl.on('line', async (line) => {
    try {
      // Skip empty lines
      if (!line.trim()) {
        return;
      }

      // Parse the JSON-RPC request
      const request = JSON.parse(line);

      // Check if this is a notification (no id field)
      const isNotification = request.id === undefined || request.id === null;

      if (isNotification) {
        console.error(`→ Received notification: ${request.method}`);
        // Process notification but don't send response
        await processRequest(request);
        return;
      }

      console.error(`→ Received request: ${request.method} (id: ${request.id})`);

      // Increment pending request counter
      pendingRequests++;

      try {
        // Process the request
        const response = await processRequest(request);

        if (response) {
          console.error(`← Sending response for id: ${response.id}`);
          process.stdout.write(JSON.stringify(response) + '\n');
        }

      } catch (error) {
        console.error(`Error processing request: ${error.message}`);

        const errorResp = errorResponse(request.id, -32603, `Wrapper error: ${error.message}`);
        process.stdout.write(JSON.stringify(errorResp) + '\n');

      } finally {
        pendingRequests--;

        if (isClosing && pendingRequests === 0) {
          console.error('All requests completed, shutting down...');
          process.exit(0);
        }
      }

    } catch (error) {
      console.error(`Error parsing request: ${error.message}`);

      // Try to extract request id for error response
      let requestId = null;
      try {
        const req = JSON.parse(line);
        requestId = req.id;
      } catch (e) {
        // Ignore parsing error
      }

      if (requestId !== null) {
        const errorResp = errorResponse(requestId, -32700, `Parse error: ${error.message}`);
        process.stdout.write(JSON.stringify(errorResp) + '\n');
      }
    }
  });

  rl.on('close', () => {
    console.error('Stdin closed, waiting for pending requests...');
    isClosing = true;

    if (pendingRequests === 0) {
      console.error('MCP GraphQL Wrapper shutting down...');
      process.exit(0);
    }
  });
}

// Handle process signals
process.on('SIGINT', () => {
  console.error('Received SIGINT, shutting down...');
  process.exit(0);
});

process.on('SIGTERM', () => {
  console.error('Received SIGTERM, shutting down...');
  process.exit(0);
});

// Start the wrapper
main().catch((error) => {
  console.error(`Fatal error: ${error.message}`);
  process.exit(1);
});
