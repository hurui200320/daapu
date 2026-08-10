package info.skyblond.daapu.server

import info.skyblond.daapu.AppConfig
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessagePart
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pins the request → neutral parts mapping done by [ChatRunService.prepareRun].
 */
class ChatRunServiceTest {

    private val service = ChatRunService(
        AppConfig(
            databaseUrl = "jdbc:postgresql://localhost:5432/postgres",
            databaseUser = "postgres",
            databasePassword = "postgres",
            llmApiKey = "test",
            llmBaseUrl = "http://localhost:9",
            httpPort = 8080,
        )
    )

    private fun request(
        text: String? = null,
        images: List<String> = emptyList(),
        model: String = "bifrost/cerebras/gpt-oss-120b",
    ) = SendMessageRequest(text = text, images = images.map { ImagePart(it) }, model = model)

    @Test
    fun `text only maps to a text part`() {
        val setup = service.prepareRun("chat-1", request(text = "hello"))
        assertEquals(listOf(ChatMessagePart.Text("hello")), setup.parts)
        assertEquals("bifrost/cerebras/gpt-oss-120b", setup.model.id)
    }

    @Test
    fun `text and image map to text plus attachment`() {
        val dataUrl = "data:image/png;base64,AAAA"
        val setup = service.prepareRun("chat-1", request(text = "look", images = listOf(dataUrl)))
        assertEquals(2, setup.parts.size)
        val attachment = assertIs<ChatMessagePart.Attachment>(setup.parts[1])
        assertEquals(AttachmentKind.Image, attachment.kind)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
    }

    @Test
    fun `image only is allowed`() {
        // an image-only message is valid; the turn loop's capability check
        // (not the API) decides whether the model can handle it
        val setup = service.prepareRun("chat-1", request(images = listOf("data:image/jpeg;base64,BBBB")))
        assertEquals(1, setup.parts.size)
        assertIs<ChatMessagePart.Attachment>(setup.parts[0])
    }

    @Test
    fun `image with a text-only model is NOT rejected at the API`() {
        // deliberate: capability enforcement lives in the turn loop's pre-send
        // step, so history-sourced images are covered too. Pinned here so the
        // API layer doesn't grow a partial validation that misses history.
        val setup = service.prepareRun(
            "chat-1",
            request(images = listOf("data:image/png;base64,AAAA"), model = "bifrost/cerebras/gpt-oss-120b"),
        )
        assertEquals("bifrost/cerebras/gpt-oss-120b", setup.model.id)
        assertEquals(1, setup.parts.size)
    }

    @Test
    fun `blank message is rejected`() {
        assertFailsWith<BadRequestException> { service.prepareRun("chat-1", request(text = "   ")) }
        assertFailsWith<BadRequestException> { service.prepareRun("chat-1", request(text = "")) }
        assertFailsWith<BadRequestException> { service.prepareRun("chat-1", request()) }
    }

    @Test
    fun `missing model is rejected`() {
        // the server has no default model; the web UI always sends one
        assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", SendMessageRequest(text = "hi"))
        }
        assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", SendMessageRequest(text = "hi", model = "  "))
        }
    }

    @Test
    fun `malformed data url is rejected`() {
        listOf(
            "http://example.com/image.png",              // not a data URL
            "data:text/plain;base64,AAAA",               // not an image
            "data:image/png;base64,",                    // no payload
            "data:image/png,AAAA",                       // not base64
            "not even a url",
        ).forEach { url ->
            val e = assertFailsWith<BadRequestException>("url: $url") {
                service.prepareRun("chat-1", request(images = listOf(url)))
            }
            assertNotNull(e.message)
        }
    }

    @Test
    fun `invalid base64 payload is rejected`() {
        // decodes to garbage, not valid base64
        val e = assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", request(images = listOf("data:image/png;base64,@@@not-base64@@@")))
        }
        assertNotNull(e.message)
    }

    @Test
    fun `base64 with line breaks is accepted`() {
        // data URLs produced by FileReader are single-line, but folded base64
        // (whitespace-separated) is legal; whitespace must be stripped
        val setup = service.prepareRun("chat-1", request(images = listOf("data:image/png;base64,AAA\nA")))
        val attachment = assertIs<ChatMessagePart.Attachment>(setup.parts[0])
        assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
    }

    @Test
    fun `unknown model is rejected`() {
        val e = assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", request(text = "hi", model = "no/such-model"))
        }
        assertNotNull(e.message)
    }

    @Test
    fun `known models are accepted`() {
        listOf("bifrost/cerebras/gpt-oss-120b", "bifrost/cerebras/gemma-4-31b", "bifrost/novita/google/gemma-4-31b-it")
            .forEach { id ->
                val setup = service.prepareRun("chat-1", request(text = "hi", model = id))
                assertEquals(id, setup.model.id)
            }
    }

    @Test
    fun `llm base url gains the v1 api root when missing`() {
        // koog's default chat completions path was "v1/chat/completions";
        // langchain4j appends "chat/completions" to its baseUrl, so the root
        // must carry /v1 to hit the same endpoint (verified live in the #6
        // smoke: without it the gateway answers 405 Method Not Allowed)
        assertEquals("http://localhost:9/v1", "http://localhost:9".openAiApiRoot())
        assertEquals("http://localhost:9/v1", "http://localhost:9/".openAiApiRoot())
        assertEquals("https://api.example.com/v1", "https://api.example.com/v1".openAiApiRoot())
    }
}
