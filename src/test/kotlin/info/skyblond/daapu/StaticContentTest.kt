package info.skyblond.daapu

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticContentTest {

    private fun spaExists(): Boolean =
        javaClass.classLoader.getResource("static/index.html") != null

    @Test
    fun `root serves the SPA`() = testApplication {
        // The SPA is built into resources/static during the Docker build; skip
        // when the frontend hasn't been built locally.
        assumeTrue(spaExists(), "frontend build output not present")
        val db = DaapuPostgres.shared()
        application { configureApp(db.appConfig(), DaapuPostgres.sharedDataSource()) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("<div id=\"root\"></div>"))
    }

    @Test
    fun `client-side route falls back to the SPA index`() = testApplication {
        assumeTrue(spaExists(), "frontend build output not present")
        val db = DaapuPostgres.shared()
        application { configureApp(db.appConfig(), DaapuPostgres.sharedDataSource()) }
        val response = client.get("/login")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("<div id=\"root\"></div>"))
    }
}
