import js from '@eslint/js'
import globals from 'globals'
import svelte from 'eslint-plugin-svelte'
import prettierConfig from 'eslint-config-prettier'
import ts from 'typescript-eslint'

/**
 * Flat config (ESLint 10). Formatting is owned by Prettier — its config
 * turns off all stylistic rules that would fight with it.
 */
export default ts.config(
  {
    ignores: ['dist/', 'node_modules/', 'coverage/'],
  },
  js.configs.recommended,
  ...ts.configs.recommended,
  {
    // parser-free by design: only ambient environment info lives here
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
    },
  },
  ...svelte.configs['flat/recommended'],
  {
    // MUST stay scoped to script files: an unscoped languageOptions.parser
    // would override eslint-plugin-svelte's own dual parser for *.svelte
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: {
      // explicit so `.svelte.ts` rune modules (preprocessed before tsc sees
      // them) still parse as TypeScript
      parser: ts.parser,
    },
  },
  {
    rules: {
      'svelte/no-at-html-tags': 'warn',
    },
  },
  {
    // eslint-plugin-svelte claims `.svelte.ts` rune modules with
    // svelte-eslint-parser (which understands `$state` etc.) but leaves the
    // inner content on the default JS parser — re-point it at TypeScript
    files: ['**/*.svelte.ts'],
    languageOptions: {
      parserOptions: {
        parser: ts.parser,
      },
    },
  },
  {
    // Svelte templates are TS-aware via this dual-parser setup
    files: ['**/*.svelte'],
    languageOptions: {
      parserOptions: {
        parser: ts.parser,
        extraFileExtensions: ['.svelte'],
      },
    },
  },
  {
    // config files and tests run OUTSIDE the browser bundle (ESLint/Vite
    // config, Vitest's node environment). Node globals are scoped to just
    // those so a stray `process`/`Buffer` in app code stays a lint error
    files: ['**/*.config.{js,ts}', '**/*.test.ts'],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
  prettierConfig,
)
