import serverless from 'serverless-http';
import app from './server.js';
import { flush } from './metrics.js';

// Wrap the Express app for AWS Lambda + API Gateway.
// serverless-http translates the API Gateway event into an Express req/res cycle
// and returns the response in the format API Gateway expects.
const expressHandler = serverless(app, {
  // Binary content types that should be base64-encoded in the response
  // (e.g. when /api/tts returns audio data).
  binary: ['application/octet-stream', 'audio/*', 'image/*'],
});

// Metrics buffered during the request (see metrics.js) are written here, once, after the
// response object exists. Two reasons this is the flush point rather than anywhere inside the
// Express app: it is the only place guaranteed to run exactly once per invocation whatever route
// handled the request, and Lambda freezes the container the moment the handler resolves - so
// anything still sitting in the buffer at that instant would not be written out until the
// container happened to thaw for the next request, arriving minutes late and attributed to the
// wrong minute.
//
// The finally block matters: a request that threw still burned Gemini quota and still ran DB
// queries, and those are precisely the invocations whose metrics are worth having.
export const handler = async (event, context) => {
  try {
    return await expressHandler(event, context);
  } finally {
    flush();
  }
};
