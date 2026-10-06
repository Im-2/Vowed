// Writes docs/openapi.json from the route schemas (the single source of truth for validation and docs).
import { writeFileSync } from "node:fs";
import { makeWorld } from "../test/helpers/world.js";

const w = await makeWorld();
const doc = w.app.swagger();
const out = new URL("../../docs/openapi.json", import.meta.url);
writeFileSync(out, JSON.stringify(doc, null, 2) + "\n");
console.log(`wrote ${Object.keys(doc.paths ?? {}).length} paths to docs/openapi.json`);
await w.app.close();
