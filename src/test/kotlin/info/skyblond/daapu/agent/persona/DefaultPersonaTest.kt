package info.skyblond.daapu.agent.persona

import kotlin.test.*

/**
 * Pins the code-only default persona's content (the legacy
 * `# Core instruction` + `## Personality` + `# Policy` sections): the
 * reserved id, the default text, and the empty (all-namespaces) whitelist.
 * The GSG harness introduction is NOT part of the persona text — it is
 * appended at render time by `MainAgentSystemPromptService`.
 */
class DefaultPersonaTest {

    @Test
    fun `the default persona keeps the identity and personality sections`() {
        assertTrue(DEFAULT_PERSONA_SYSTEM_PROMPT.startsWith("# Core instruction"))
        assertTrue(DEFAULT_PERSONA_SYSTEM_PROMPT.contains("## Personality"))
        assertTrue(DEFAULT_PERSONA_SYSTEM_PROMPT.contains("You're the brain."))
        // the policy/jailbreak section is persona-owned text too (a custom
        // persona may drop or replace it)
        assertTrue(DEFAULT_PERSONA_SYSTEM_PROMPT.contains("# Policy"))
        assertTrue(DEFAULT_PERSONA_SYSTEM_PROMPT.contains("## SYSTEM POLICY"))
        // the harness-owned sections are NOT part of the persona text
        assertFalse(DEFAULT_PERSONA_SYSTEM_PROMPT.contains("# Harness"))
    }

    @Test
    fun `the default persona is the reserved id with an all-namespaces whitelist`() {
        val persona = defaultPersona()
        assertEquals(DEFAULT_PERSONA_ID, persona.id)
        assertEquals(DEFAULT_PERSONA_SYSTEM_PROMPT, persona.systemPrompt)
        assertEquals(emptyList(), persona.allowedNamespaces)
    }
}
