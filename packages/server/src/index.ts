import { createApp } from './app.js';

/**
 * Reference OTA server entrypoint (M10–M12).
 * MS0: Express skeleton only — no routes, no DB, no storage.
 */
const port = Number(process.env['PORT'] ?? 3100);

const app = createApp();

app.listen(port, () => {
  console.log(`@rnota/server listening on :${port} (scaffold — no routes yet)`);
});
