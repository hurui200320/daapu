package info.skyblond.daapu

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.register(username: String): HttpResponse =
        post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"password123"}""")
        }

    private suspend fun HttpClient.createChat(): Long {
        val response = post("/api/chats")
        assertEquals(HttpStatusCode.Created, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
    }

    @Test
    fun `chat lifecycle works`() = withDb {
        val client = createClient { install(HttpCookies) }
        client.register("alice")

        val id = client.createChat()

        val list = client.get("/api/chats")
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("\"id\":$id"))

        val rename = client.patch("/api/chats/$id") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"my chat"}""")
        }
        assertEquals(HttpStatusCode.OK, rename.status)
        assertTrue(rename.bodyAsText().contains("my chat"))

        val messages = client.get("/api/chats/$id")
        assertEquals(HttpStatusCode.OK, messages.status)
        val arr = json.parseToJsonElement(messages.bodyAsText()).jsonArray
        assertEquals(0, arr.size)

        val delete = client.delete("/api/chats/$id")
        assertEquals(HttpStatusCode.NoContent, delete.status)
    }

    @Test
    fun `chats are isolated between users`() = withDb {
        val alice = createClient { install(HttpCookies) }
        val bob = createClient { install(HttpCookies) }
        alice.register("alice")
        bob.register("bob")

        val id = alice.createChat()

        // Bob cannot read, rename, delete, or message Alice's chat.
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/chats/$id").status)
        assertEquals(HttpStatusCode.NotFound, bob.patch("/api/chats/$id") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"hijack"}""")
        }.status)
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/chats/$id").status)
        assertEquals(HttpStatusCode.NotFound, bob.post("/api/chats/$id/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hello"}""")
        }.status)
    }

    @Test
    fun `send message streams an SSE echo reply`() = withDb {
        val client = createClient { install(HttpCookies) }
        client.register("carol")
        val id = client.createChat()

        val response = client.post("/api/chats/$id/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hello there"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Text.EventStream))

        val body = response.bodyAsText()
        assertTrue(body.contains("data: "))
        assertTrue(body.contains("[DONE]"))
        // The echo streams words incrementally: first frame is a prefix.
        assertTrue(body.contains("\"content\":\"Got it:\""))
        assertTrue(body.contains("\"content\":\"Got it: hello there\""))
    }

    @Test
    fun `echo reply is persisted after streaming`() = withDb {
        val client = createClient { install(HttpCookies) }
        client.register("dave")
        val id = client.createChat()

        client.post("/api/chats/$id/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }

        val messages = client.get("/api/chats/$id")
        val arr = json.parseToJsonElement(messages.bodyAsText()).jsonArray
        assertEquals(2, arr.size)
        assertEquals("USER", arr[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("ASSISTANT", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(arr[1].jsonObject["content"]!!.jsonPrimitive.content.contains("hi"))
    }
}
