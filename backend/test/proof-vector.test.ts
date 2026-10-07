import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { canonicalPackageBytes } from "../src/proofs/verify.js";

/** The Android app builds the signed bytes with its own canonical-JSON code; both must agree on these vectors. */
describe("proof package canonical bytes (shared with the Android app)", () => {
  const vectors = JSON.parse(readFileSync(new URL("../../shared/test-vectors/proof-package.json", import.meta.url), "utf8")) as { package: Record<string, unknown>; canonical: string }[];
  it("has vectors", () => expect(vectors.length).toBeGreaterThanOrEqual(5));
  it.each(vectors.map((v, i) => [i, v] as const))("vector %i matches", (_i, v) => {
    expect(canonicalPackageBytes(v.package as never).toString("utf8")).toBe(v.canonical);
  });
});
