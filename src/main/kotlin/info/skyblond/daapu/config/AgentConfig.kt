package info.skyblond.daapu.config

import info.skyblond.daapu.agent.tool.validateToolNamespaceSyntax
import kotlinx.serialization.Serializable

/**
 * The agent settings: the main (chat loop) agent and the investigate
 * sub-agent (`agent/pipeline/investigate/InvestigatorService.kt`, a
 * `runCollect` tool loop that gathers information from the ELTM (read-only)
 * and the web (MCP tools) on behalf of the main agent).
 */
@Serializable
data class AgentConfig(
    /** The main (chat loop) agent settings. */
    val main: MainAgentConfig = MainAgentConfig(),
    /** The investigate sub-agent settings. */
    val investigator: InvestigatorConfig,
) {
    fun validate() {
        main.validate()
        investigator.validate()
    }
}

/**
 * The main (chat loop) agent settings. Today only the tool-result
 * truncation cap that the loop's `LengthSafeToolProvider` (see
 * `agent/tool/LengthSafeToolProvider.kt`) enforces on every successful
 * tool result.
 */
@Serializable
data class MainAgentConfig(
    /**
     * The tool-result truncation cap in chars for the MAIN chat loop's
     * `LengthSafeToolProvider`: a successful tool result whose merged text
     * exceeds it is merged into one part, truncated to the cap (the
     * truncation marker is budgeted INSIDE the cap) and handed to the
     * model as-is. Chars, not tokens, by design (token estimation is
     * unreliable across providers/models); error results are never
     * truncated (they are short failure descriptions the model needs
     * verbatim to recover). Must be positive.
     */
    val toolResultLimit: Int = 40000,
) {
    fun validate() {
        require(toolResultLimit > 0) {
            "agent.main.toolResultLimit must be > 0, got $toolResultLimit"
        }
    }
}

/**
 * The investigate sub-agent settings (see [AgentConfig]). The model id is
 * REQUIRED and references the catalog (`agent/ModelCatalog.kt`); it is
 * resolved once at startup by the DI container (`di/AppModule.kt`) like
 * the memory pipeline models — unknown ids and a model without tool-call
 * support fail fast. [allowedNamespaces] is the sub-agent's tool whitelist
 * over its OWN tool set (the read-only `eltm` tools plus the MCP servers,
 * built separately from the chat loop's set): REQUIRED, non-empty, and
 * every entry must be a namespace that set serves (a typo — or an attempt
 * to whitelist `gsg` itself, which would enable recursion — fails fast at
 * boot via the `WhitelistedToolProvider` construction).
 */
@Serializable
data class InvestigatorConfig(
    /** Catalog LLM id of the investigate sub-agent (a tool loop); REQUIRED. */
    val model: String,
    /** Round cap for the investigate tool loop; `0` = unlimited. */
    val maxRounds: Int = 150,
    /**
     * The namespaces the investigate sub-agent may execute (a whitelist
     * over its own tool set — the read-only `eltm` tools plus the MCP
     * servers); REQUIRED, non-empty. Entries are validated like any tool
     * namespace.
     */
    val allowedNamespaces: List<String>,
    /**
     * The tool-result truncation cap in chars for the sub-agent's own
     * `LengthSafeToolProvider` (see `agent/tool/LengthSafeToolProvider.kt`),
     * wrapping its whitelisted tool set: a successful tool result whose
     * merged text exceeds it is truncated to the cap. Chars, not tokens,
     * by design (token estimation is unreliable across
     * providers/models); error results are never truncated (they are
     * short failure descriptions the model needs verbatim to recover).
     * Must be positive.
     */
    val toolResultLimit: Int = 40000,
) {
    fun validate() {
        require(model.isNotBlank()) { "agent.investigator.model must not be blank" }
        require(maxRounds >= 0) {
            "agent.investigator.maxRounds must be >= 0, got $maxRounds"
        }
        require(allowedNamespaces.isNotEmpty()) {
            "agent.investigator.allowedNamespaces must not be empty"
        }
        allowedNamespaces.forEach {
            require(it.isNotBlank()) {
                "agent.investigator.allowedNamespaces entries must not be blank"
            }
            validateToolNamespaceSyntax(it, "agent.investigator.allowedNamespaces")
        }
        require(toolResultLimit > 0) {
            "agent.investigator.toolResultLimit must be > 0, got $toolResultLimit"
        }
    }
}
