import express, { type Express } from 'express';

/**
 * Create the Express application shell.
 * No routes in MS0 — device/mgmt/ingestion APIs land in MS7.
 */
export function createApp(): Express {
  const app = express();

  app.disable('x-powered-by');
  app.use(express.json({ limit: '1mb' }));

  return app;
}
