import { buildApp } from "./app.js";

const port = Number(process.env.PORT ?? 8787);
buildApp().listen({ port, host: "0.0.0.0" }).then(() => console.log(`vowed-backend on :${port}`));
