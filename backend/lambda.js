import serverless from 'serverless-http';
import app from './server.js';

// Wrap the Express app for AWS Lambda + API Gateway.
// serverless-http translates the API Gateway event into an Express req/res cycle
// and returns the response in the format API Gateway expects.
export const handler = serverless(app, {
  // Binary content types that should be base64-encoded in the response
  // (e.g. when /api/tts returns audio data).
  binary: ['application/octet-stream', 'audio/*', 'image/*'],
});
