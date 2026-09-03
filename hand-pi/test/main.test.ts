import { describe, expect, it } from "vitest";
import { startServer } from "../src/main.js";

describe("startServer token requirement", () => {
  it("refuses to start with a missing or blank token", async () => {
    // the entrypoint surfaces this as a startup failure (process.exit(1));
    // the guard lives in startServer so every caller gets the same guarantee
    for (const token of ["", "   "]) {
      await expect(startServer(0, token)).rejects.toThrow(/HAND_TOKEN/);
    }
  });
});
