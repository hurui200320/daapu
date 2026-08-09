package info.skyblond.daapu.langchain4j

import info.skyblond.daapu.agent.ModelCapabilityException
import info.skyblond.daapu.history.AttachmentKind
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the prompt-vs-model capability check (framework-agnostic port of the
 * koog-typed check in `agent/ModelCapabilityCheck.kt`, which stays live until
 * the runtime switches over in #6).
 *
 * The check takes the set of attachment kinds extracted from the FULL prompt
 * (loaded history + new input): images can enter the prompt from the request
 * OR from stored history (sent to a vision model earlier, re-sent when the
 * chat switches to a text-only model), so the caller must scan both — the
 * check itself only maps kinds to required capabilities.
 */
class ModelCapabilityCheckTest {

    private val catalog = ModelCatalog("http://gateway.example/v1")
    private val gptOss = catalog.findModel("cerebras/gpt-oss-120b")!!
    private val cerebrasGemma = catalog.findModel("cerebras/gemma-4-31b")!!
    private val novitaGemma = catalog.findModel("novita/google/gemma-4-31b-it")!!

    @Test
    fun `text-only prompt passes on a text-only model`() {
        checkPromptContentCapabilities(emptySet(), gptOss)
    }

    @Test
    fun `image in the prompt fails on a text-only model`() {
        assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(setOf(AttachmentKind.Image), gptOss)
        }
    }

    @Test
    fun `image passes on a vision model`() {
        checkPromptContentCapabilities(setOf(AttachmentKind.Image), cerebrasGemma)
        checkPromptContentCapabilities(setOf(AttachmentKind.Image), novitaGemma)
    }

    @Test
    fun `video audio and file fail on every catalog model`() {
        for (kind in listOf(AttachmentKind.Video, AttachmentKind.Audio, AttachmentKind.File)) {
            for (model in catalog.models) {
                assertFailsWith<ModelCapabilityException>("$kind on ${model.id}") {
                    checkPromptContentCapabilities(setOf(kind), model)
                }
            }
        }
    }

    @Test
    fun `error message names the model and the offending content`() {
        val e = assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(setOf(AttachmentKind.Image), gptOss)
        }
        assertNotNull(e.message)
        val message = e.message!!
        // a useful message tells the user what to do next
        assertTrue(message.contains(gptOss.id), "should name the model: $message")
        assertTrue(message.contains("image"), "should mention image: $message")
    }
}
