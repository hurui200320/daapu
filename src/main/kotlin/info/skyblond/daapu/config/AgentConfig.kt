package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The sub-agent settings: the investigate agent (`agent/oneshot/investigate/
 * InvestigatorService.kt`), a `runCollect` tool loop that gathers
 * information from the ELTM (read-only) and the web (MCP tools) on behalf
 * of the main agent.
 */
@Serializable
data class AgentConfig(
    /** The investigate sub-agent settings. */
    val investigator: InvestigatorConfig,
) {
    fun validate() {
        investigator.validate()
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
    }
}
