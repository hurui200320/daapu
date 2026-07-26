# AGENTS.md

Guidance for AI agents (and humans) working in this repository.

## Project

XMPP + OMEMO sidecar for the XMPP chatbot. Python 3.14, managed with `uv`.

## Environment setup

```bash
uv sync --extra dev          # install runtime + dev deps (pyright, ruff)
```

## Verification commands

Run **all three** after any source change. They must exit clean.

```bash
uv run --extra dev pyright src/                       # type check (strict)
uv run --extra dev ruff check src/                   # lint
uv run --extra dev ruff format --check src/          # format check
```

To auto-fix lint + format:

```bash
uv run --extra dev ruff check --fix src/
uv run --extra dev ruff format src/
```

## Type-checking policy

- `typeCheckingMode = "strict"` and `reportExplicitAny = "error"` are on.
  Do **not** use `Any` in new code. Use `object` or a proper type instead.
- slixmpp's stubs are incomplete. At the slixmpp boundary use targeted
  `cast(...)` calls and, only when unavoidable, `# pyright: ignore[<rule>]`
  with a comment explaining why. Do not relax the global config.
- The framework-required `*args: Any, **kwargs: Any` in `omemo_plugin.py`
  `__init__` is the only accepted `Any` usage (parent class contract).

## Code style

- `from __future__ import annotations` is the convention — keep it.
- Ruff enforces annotation rules (`ANN*`); every function parameter and
  return value must be annotated. `self`/`cls` are exempt.
- Local variables do not require explicit annotations when pyright can
  infer the type; annotate when inference would yield `Unknown`/`Any`.
- Add comments to explain non-obvious or unusual setup/logic. 
- DO NOT over comments.
