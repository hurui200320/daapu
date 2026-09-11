# Utility scripts

Dev-time one-off tools under this package (`script/`). They are not part of
the server: nothing here is wired into the API, and they only reuse the
project's JSON formats and codecs. They still compile and ship with the main
source set (so they can reuse everything), but the server never calls them.

## Running a script

The generic Gradle runner overrides the `run` task's main class for one
invocation (see `build.gradle.kts`):

```bash
./gradlew run -PmainClass=<fully.qualified.MainKt> --args="<script arguments>"
```

`--args` is split on whitespace, honoring embedded single/double quotes. The
server's own entrypoint (`./gradlew run` without `-PmainClass`) and the
Docker image are unaffected.

## Scripts

### `digest/transform/SillyTavernTransformer.kt`

Transforms a SillyTavern chat export into this project's neutral chat
format, so an old ST chat can be continued here.

```bash
./gradlew run \
  -PmainClass=info.skyblond.daapu.script.digest.transform.SillyTavernTransformerKt \
  --args="/path/st-export.jsonl /path/images /path/output 'My chat title'"
```

Arguments: `<st-export.jsonl> <imagesDir> <outputBase> [title]` — the JSONL
export, the folder with the referenced images (ST media urls like
`/user/images/<character>/x.png` resolve by file name to
`<imagesDir>/x.png`), the output path base, and an optional chat title
(defaults to the export file's name; must be non-blank).

Input requirements: message `send_date` fields must be ISO 8601 — recent
ST versions write UTC `toISOString()` output (e.g.
`2026-01-01T02:05:00.000Z`); local-offset ISO forms (e.g.
`2026-01-01T10:05:00+08:00`) are accepted too. Older exports' humanized
dates or epoch millis are rejected, naming the line and value. Lines that
are not JSON objects, or without `send_date`/`mes` — the ST header,
truncated or plugin-appended lines — are skipped, each with a `[warn]` on
stderr carrying the full line: review the warnings, since skipped content
is absent from the output.

One run writes BOTH files:

- `<outputBase>.messages.json` — the raw neutral chat format
  (`ChatCodec.encodeChat`), byte-identical to what `GET /api/chats/{id}/chat`
  serves; for processing by code.
- `<outputBase>.export.json` — the `{title, messages}` payload the webui's
  import accepts (the same shape as `GET /api/chats/{id}/export`); import
  it via the web UI to continue the chat.

The transform's exact semantics (prologue wrapping, timestamps, the
fail-fast checks) live in `transformSillyTavernChat`'s KDoc.
