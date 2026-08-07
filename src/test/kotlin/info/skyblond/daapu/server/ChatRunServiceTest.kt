package info.skyblond.daapu.server

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import info.skyblond.daapu.AppConfig
import info.skyblond.daapu.koog.client.Cerebras
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pins the request → koog parts mapping done by [ChatRunService.prepareRun].
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
        model: String = Cerebras.GPT_OSS_120B.id,
    ) = SendMessageRequest(text = text, images = images.map { ImagePart(it) }, model = model)

    @Test
    fun `text only maps to a text part`() {
        val setup = service.prepareRun("chat-1", request(text = "hello"))
        assertEquals(listOf(MessagePart.Text("hello")), setup.parts)
        assertEquals(Cerebras.GPT_OSS_120B, setup.model)
    }

    @Test
    fun `text and image map to text plus attachment`() {
        val dataUrl = "data:image/png;base64,AAAA"
        val setup = service.prepareRun("chat-1", request(text = "look", images = listOf(dataUrl)))
        assertEquals(2, setup.parts.size)
        val attachment = assertIs<MessagePart.Attachment>(setup.parts[1])
        val source = assertIs<AttachmentSource.Image>(attachment.source)
        assertEquals("png", source.format)
        assertEquals("image/png", source.mimeType)
        assertEquals(AttachmentContent.Binary.Base64("AAAA"), source.content)
    }

    @Test
    fun `image only is allowed`() {
        // an image-only message is valid; the strategy's capability check
        // (not the API) decides whether the model can handle it
        val setup = service.prepareRun("chat-1", request(images = listOf("data:image/jpeg;base64,BBBB")))
        assertEquals(1, setup.parts.size)
        assertIs<MessagePart.Attachment>(setup.parts[0])
    }

    @Test
    fun `image with a text-only model is NOT rejected at the API`() {
        // deliberate: capability enforcement lives in the strategy's preprocess
        // node, so history-sourced images are covered too. Pinned here so the
        // API layer doesn't grow a partial validation that misses history.
        val setup = service.prepareRun(
            "chat-1",
            request(images = listOf("data:image/png;base64,AAAA"), model = Cerebras.GPT_OSS_120B.id),
        )
        assertEquals(Cerebras.GPT_OSS_120B, setup.model)
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
        val attachment = assertIs<MessagePart.Attachment>(setup.parts[0])
        val source = assertIs<AttachmentSource.Image>(attachment.source)
        assertEquals(AttachmentContent.Binary.Base64("AAAA"), source.content)
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
        listOf(Cerebras.GPT_OSS_120B.id, Cerebras.Gemma4_31B.id, "novita/google/gemma-4-31b-it")
            .forEach { id ->
                val setup = service.prepareRun("chat-1", request(text = "hi", model = id))
                assertEquals(id, setup.model.id)
            }
    }
}
