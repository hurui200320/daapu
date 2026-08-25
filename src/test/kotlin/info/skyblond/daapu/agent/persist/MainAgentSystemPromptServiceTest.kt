package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.persona.Persona
import info.skyblond.daapu.agent.persona.defaultPersona
import kotlin.test.*

/**
 * Pins the main agent's system prompt assembly: the persona text (verbatim,
 * including its indentation) followed by the GSG harness introduction (the
 * legacy `# Harness` + context-injection + developer note sections), always
 * rendered in full regardless of the persona's whitelist. Gating the
 * `gsg__investigate` documentation on the whitelist is planned (see the
 * TODO in `MainAgentSystemPromptService`).
 */
class MainAgentSystemPromptServiceTest {

    private val service = MainAgentSystemPromptService(isDevelopment = false)

    @Test
    fun `the assembly is persona text, a blank line, then the gsg introduction`() {
        val persona = Persona(1L, "Writer", "You are a writer.", listOf("gsg"))
        val rendered = service.render(persona)
        assertTrue(
            rendered.startsWith("You are a writer.\n\n# Harness"),
            "the persona text is followed verbatim by the harness introduction",
        )
        // legacy section order preserved for the default persona
        val legacy = MainAgentSystemPromptService(isDevelopment = true)
            .render(defaultPersona())
        listOf(
            "# Core instruction",
            "## Personality",
            "# Policy",
            "## SYSTEM POLICY",
            "# Harness",
            "## Context injection",
            "### Real-time info",
            "### Memories injection",
            "## Developer note",
        ).forEach { section ->
            assertTrue(
                legacy.contains(section),
                "the assembled default prompt keeps the legacy section '$section'",
            )
        }
        val coreIndex = legacy.indexOf("# Core instruction")
        val policyIndex = legacy.indexOf("# Policy")
        assertTrue(coreIndex in 0 until policyIndex, "the persona comes before the policy")
    }

    @Test
    fun `the full introduction is rendered regardless of the whitelist`() {
        // gating the gsg documentation on the whitelist is planned (next
        // commit): today even a persona without the `gsg` namespace is told
        // about gsg__investigate
        val rendered = service.render(
            Persona(1L, "Plain", "You are a plain assistant.", listOf("calc"))
        )
        assertTrue(rendered.startsWith("You are a plain assistant.\n\n# Harness"))
        assertTrue(rendered.contains("## External long term memories (ELTM)"))
        assertTrue(rendered.contains("gsg__investigate"))
        assertTrue(rendered.contains("## Context injection"))
        assertTrue(rendered.contains("eltm-updated"))
        // the policy is persona-owned text: only the DEFAULT persona carries it
        assertFalse(rendered.contains("# Policy"))
    }

    @Test
    fun `a uniformly indented persona prompt is preserved verbatim`() {
        // the persona text is user-authored: no trimIndent normalization may
        // strip a uniform indentation — it is content, not formatting
        val persona = Persona(1L, "Writer", "  You are a writer.\n  Second line.", listOf("gsg"))
        val rendered = service.render(persona)
        assertTrue(
            rendered.startsWith("  You are a writer.\n  Second line.\n\n# Harness"),
            "the persona text survives the assembly byte-identical",
        )
    }

    @Test
    fun `the default persona renders the full legacy prompt`() {
        // the default persona's empty whitelist = all namespaces; the policy
        // is part of its text, the gsg documentation is always appended
        val rendered = service.render(defaultPersona())
        assertTrue(rendered.contains("# Policy"))
        assertTrue(rendered.contains("gsg__investigate"))
        assertTrue(rendered.contains("# Harness"))
    }
}
