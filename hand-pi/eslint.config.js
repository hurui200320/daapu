import js from "@eslint/js";
import globals from "globals";
import prettierConfig from "eslint-config-prettier";
import ts from "typescript-eslint";

/**
 * Flat config (ESLint 10), mirroring the frontend's setup minus the Svelte
 * parts. Formatting is owned by Prettier — its config turns off all
 * stylistic rules that would fight with it.
 */
export default ts.config(
  {
    ignores: ["dist/", "node_modules/", "coverage/"],
  },
  js.configs.recommended,
  ...ts.configs.recommended,
  {
    // the hand is a pure Node service: node globals apply to every file
    // (unlike the frontend, where app code must stay browser-pure)
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: { ...globals.node },
    },
  },
  prettierConfig,
);
