package info.skyblond.daapu.agent.persona

import kotlinx.serialization.Serializable

/**
 * One entry of the personas export/import payload (`GET /api/personas/export`
 * answers a JSON array of these; `POST /api/personas/import` takes the same
 * shape back — the same file round-trips, see `PersonaService.exportPersonas`
 * /`importPersonas`). Deliberately NOT `PersonaSaveRequest` despite the
 * matching shape: that DTO defaults [allowedNamespaces] to empty, and ktor's
 * Json serializes with `encodeDefaults = false` — an export through it would
 * silently DROP an empty list (the "all namespaces" marker; the same pitfall
 * the chat export route, ChatsRoute.kt, documents). Both fields are required
 * here, so an export through ContentNegotiation always writes `[]` and an
 * import rejects a file that omits a field.
 */
@Serializable
data class PersonaExportEntry(
    val name: String,
    val systemPrompt: String,
    val allowedNamespaces: List<String>,
)

/**
 * The `POST /api/personas/import` response: the imported names split by
 * outcome, entry order preserved. A skipped name matched an existing persona
 * row on name + prompt + namespace set; a created name did not (duplicates
 * by name can appear in both lists when the file carries the same name twice
 * or the store already holds same-name rows).
 */
@Serializable
data class PersonaImportSummary(
    val created: List<String>,
    val skipped: List<String>,
)
