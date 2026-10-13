#!/usr/bin/env node

/**
 * MCP Local Wrapper - Stdio to HTTP Bridge
 *
 * This script allows Claude Desktop to connect to a local/network MCP server
 * by bridging stdio (used by Claude Desktop) to HTTP (used by your server).
 *
 * Usage:
 *   node mcp-local-wrapper.js [server-url]
 *
 * Example:
 *   node mcp-local-wrapper.js https://your-enterfind-instance.example.com/mcp
 */

const readline = require('readline');
const https = require('https');
const http = require('http');
const { URL } = require('url');

// Get server URL from command line or use default
const SERVER_URL = process.argv[2] || 'http://localhost:8080/mcp';

// Parse the URL to determine http vs https
const serverUrl = new URL(SERVER_URL);
const isHttps = serverUrl.protocol === 'https:';
const httpModule = isHttps ? https : http;

// Configure for self-signed certificates (optional - remove in production)
const agent = isHttps ? new https.Agent({
  rejectUnauthorized: false // Allow self-signed certificates
}) : undefined;

/**
 * Send a JSON-RPC request to the HTTP MCP server
 */
function sendRequest(jsonRpcRequest) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(jsonRpcRequest);

    const options = {
      hostname: serverUrl.hostname,
      port: serverUrl.port || (isHttps ? 443 : 80),
      path: serverUrl.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
        'Accept': 'application/json'
      },
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
          reject(new Error(`Failed to parse response: ${error.message}`));
        }
      });
    });

    req.on('error', (error) => {
      reject(new Error(`HTTP request failed: ${error.message}`));
    });

    req.write(data);
    req.end();
  });
}

/**
 * Main stdio loop
 * Reads JSON-RPC requests from stdin, forwards to HTTP server, writes responses to stdout
 */
async function main() {
  // Log to stderr (not stdout, which is used for MCP communication)
  console.error(`MCP Local Wrapper starting...`);
  console.error(`Server URL: ${SERVER_URL}`);
  console.error(`Waiting for JSON-RPC requests on stdin...`);

  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
  });

  // Track pending requests to ensure they complete before exit
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

      // Check if this is a notification (no id field) vs a request (has id field)
      const isNotification = request.id === undefined || request.id === null;

      if (isNotification) {
        console.error(`→ Received notification: ${request.method} (no response expected)`);
        // Notifications do not get responses per JSON-RPC 2.0 spec
        // Just acknowledge and ignore
        return;
      }

      console.error(`→ Received request: ${request.method} (id: ${request.id})`);

      // Increment pending request counter
      pendingRequests++;

      try {
        // Forward to HTTP server
        const response = await sendRequest(request);

        console.error(`← Sending response for id: ${response.id}`);

        // Write response to stdout (for Claude Desktop)
        process.stdout.write(JSON.stringify(response) + '\n');

      } catch (error) {
        console.error(`Error forwarding request: ${error.message}`);

        // Send error response
        const errorResponse = {
          jsonrpc: '2.0',
          id: request.id,
          error: {
            code: -32603,
            message: `Wrapper error: ${error.message}`
          }
        };

        process.stdout.write(JSON.stringify(errorResponse) + '\n');
      } finally {
        // Decrement pending request counter
        pendingRequests--;

        // If we're closing and no more pending requests, exit
        if (isClosing && pendingRequests === 0) {
          console.error('All requests completed, shutting down...');
          process.exit(0);
        }
      }

    } catch (error) {
      console.error(`Error processing request: ${error.message}`);

      // Try to extract request id for error response
      let requestId = null;
      try {
        const req = JSON.parse(line);
        requestId = req.id;
      } catch (e) {
        // Ignore parsing error
      }

      // Send error response
      const errorResponse = {
        jsonrpc: '2.0',
        id: requestId,
        error: {
          code: -32603,
          message: `Parse error: ${error.message}`
        }
      };

      process.stdout.write(JSON.stringify(errorResponse) + '\n');
    }
  });

  rl.on('close', () => {
    console.error('Stdin closed, waiting for pending requests...');
    isClosing = true;

    // If no pending requests, exit immediately
    if (pendingRequests === 0) {
      console.error('MCP Local Wrapper shutting down...');
      process.exit(0);
    }

    // Otherwise, wait for pending requests to complete (handled in finally block above)
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
