package info.skyblond.daapu

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthIntegrationTest {

    private suspend fun HttpClient.register(username: String, password: String): HttpResponse =
        post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }

    private suspend fun HttpClient.login(username: String, password: String): HttpResponse =
        post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }

    private suspend fun HttpClient.logout(): HttpResponse =
        post("/api/auth/logout")

    private suspend fun HttpClient.me(): HttpResponse =
        get("/api/auth/me")

    @Test
    fun `register then me returns the user`() = withDb {
        val client = createClient { install(HttpCookies) }
        val register = client.register("alice", "password123")
        assertEquals(HttpStatusCode.Created, register.status)

        val me = client.me()
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("alice"))
    }

    @Test
    fun `duplicate username is rejected`() = withDb {
        val client = createClient { install(HttpCookies) }
        client.register("bob", "password123")
        val second = client.register("bob", "password123")
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun `wrong password is rejected`() = withDb {
        val client = createClient { install(HttpCookies) }
        client.register("carol", "password123")
        val login = client.login("carol", "wrongpassword")
        assertEquals(HttpStatusCode.Unauthorized, login.status)
    }

    @Test
    fun `login sets a session and logout clears it`() = withDb {
        val client = createClient { install(HttpCookies) }
        client.register("dave", "password123")

        // Registering logs the user in, so the session is already established.
        assertEquals(HttpStatusCode.OK, client.me().status)

        val logout = client.logout()
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, client.me().status)

        val login = client.login("dave", "password123")
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals(HttpStatusCode.OK, client.me().status)
    }

    @Test
    fun `short password is rejected`() = withDb {
        val client = createClient { install(HttpCookies) }
        val register = client.register("erin", "short")
        assertEquals(HttpStatusCode.BadRequest, register.status)
    }
}
