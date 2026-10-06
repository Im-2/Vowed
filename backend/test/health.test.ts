import { describe, it, expect } from "vitest";
import { buildApp } from "../src/app.js";

describe("health", () => {
  it("returns ok", async () => {
    const res = await buildApp().inject({ method: "GET", url: "/v1/health" });
    expect(res.statusCode).toBe(200);
    expect(res.json().ok).toBe(true);
  });
});
