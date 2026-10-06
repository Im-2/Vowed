import { main } from "./server.js";

main().catch((e) => {
  // configuration errors never include secret values
  console.error(e instanceof Error ? e.message : "fatal error");
  process.exit(1);
});
