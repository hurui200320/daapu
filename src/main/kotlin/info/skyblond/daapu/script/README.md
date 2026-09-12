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

### `digest/DigestLLMChat.kt`

Manually replays a neutral-format chat (e.g. the transformer's
`<outputBase>.messages.json`) through the PRODUCTION compaction and
memory-extraction stages, WITHOUT importing it as a chat and WITHOUT
touching the database, writing the extracted facts to a text file for
manual review — review the file, then feed the facts into the web UI's
ELTM digest tab to record them.

Why not import-and-delete: an external chat may be far longer than what
this system's models can process at once (e.g. a 1M-context LLM's export
against our 256K windows), and one giant extraction over the whole chat
is exactly what the memory extractor cannot do reliably. The replay
instead feeds the extractor window-sized batches, so every bit of the
chat passes through it at a size the configured models handle.

```bash
./gradlew run \
  -PmainClass=info.skyblond.daapu.script.digest.DigestLLMChatKt \
  --args="/path/chat.messages.json /path/facts.txt 8 3"
```

Arguments: `<chat.messages.json> <factsOutput.txt> [compactionRounds=8]
[contextRounds=3]` — the chat file (the neutral format, decoded with the
stored-chat invariants), the output text file, and the two window knobs
(round counts, not messages).

The models and the hand endpoint come from the server's own
`config.jsonc` (`memory.compactModel`, `memory.eltm.extractionModel`,
`hand.*`): the hand-pi service must be running, but no database and no
HTTP server is needed next to the script (the one-shot runs are
tool-less). The output file is truncated at start and flushed per batch,
so a crashed run keeps its completed batches for review; a re-run
restarts the replay from scratch.

The replay semantics live in `replayChatForDigest`'s KDoc (in
`DigestLLMChat.kt`): window-by-window compaction with extraction over
each dropped region (the running summary included), a final residue
batch, every raw round extracted exactly once, sentinel batches skipped.

Tuning: the knobs count user ROUNDS, not messages — a chat with huge
single messages (walls of text, many images) can still overflow the
models' windows; lower the knobs in that case.
