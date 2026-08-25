package info.skyblond.daapu.agent.persona

import kotlinx.serialization.Serializable

/**
 * One agent persona: the persona half of the main agent's system prompt
 * (the GSG harness introduction is appended by
 * `agent/persist/MainAgentSystemPromptService.kt` when the prompt is
 * rendered) plus a
 * namespace whitelist over the chat loop's tool set.
 *
 * [allowedNamespaces] is a whitelist of tool namespaces (MCP servers and
 * `gsg`, the loop's own combined set); an EMPTY list means ALL namespaces
 * the chat loop serves — the default persona's shape. The whitelist filters
 * the loop's tool advertisements per request (`WhitelistedToolProvider`,
 * built in `ChatRunService.prepareRun`).
 *
 * Also the wire type of the `/api/personas` routes.
 */
@Serializable
data class Persona(
    val id: Long,
    val name: String,
    val systemPrompt: String,
    val allowedNamespaces: List<String>,
) {
    /**
     * True when this persona may use the tools of [namespace]: the whitelist
     * is empty (ALL namespaces, the default persona's shape) or contains the
     * namespace. Used to gate the harness's own behavior on the persona's
     * tool access — e.g. the `gsg` namespace decides whether the prompt
     * documents `gsg__investigate` and whether the ELTM context injection is
     * rendered.
     */
    fun serves(namespace: String): Boolean =
        allowedNamespaces.isEmpty() || namespace in allowedNamespaces
}

/**
 * The reserved id of the DEFAULT persona, which lives ONLY in code
 * (`agent/persona/DefaultPersona.kt`) — never in the `personas` table — so a
 * prompt update needs no data sync. The persona API rejects it on
 * create/update/delete; a request or chat record carrying it resolves to the
 * code constant. The sentinel is 0: a BIGSERIAL identity starts at 1 (see
 * `V2__personas.sql`), so no `personas` row can ever collide with it.
 * Mirrors the `chats.persona_id` column default in `V2__personas.sql`.
 */
const val DEFAULT_PERSONA_ID: Long = 0
