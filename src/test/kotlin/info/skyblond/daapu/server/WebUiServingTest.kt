package info.skyblond.daapu.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the packaged web UI serving (`WebServer.staticWebUi`): the deployed
 * image resolves the frontend dist out of the `frontend` classpath package
 * (populated by the Docker build), while development runs without the
 * package and answers 404 (the UI lives on the vite dev server there). The
 * test classpath carries a stub `frontend` package (see
 * `src/test/resources/frontend/`), so the deployed behavior is testable
 * without Docker; the absent-package case passes a bogus package name.
 */
class WebUiServingTest {

    @Test
    fun `the packaged web UI is served from the resource package`() {
        testApplication {
            application {
                // the ETag/304 half of the caching contract requires this
                // install (see module) — the static responder only attaches
                // the versions
                install(ConditionalHeaders)
                routing {
                    staticWebUi()
                }
            }
            val root = client.get("/")
            assertEquals(HttpStatusCode.OK, root.status)
            assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), root.contentType())
            assertTrue(
                root.bodyAsText().contains("daapu test ui"),
                "GET / must serve the package's index.html: ${root.bodyAsText()}",
            )

            val asset = client.get("/assets/app.test.js")
            assertEquals(HttpStatusCode.OK, asset.status)
            // ktor's mime table maps .js to text/javascript
            assertTrue(
                asset.contentType()?.withoutParameters()?.toString() in
                        listOf("text/javascript", "application/javascript"),
                "asset content type: ${asset.contentType()}",
            )

            // index.html is also reachable under its own name
            val named = client.get("/index.html")
            assertEquals(HttpStatusCode.OK, named.status)

            // caching contract (see staticWebUi): content-hashed assets cache
            // long, index.html is no-cache, and both carry a strong ETag that
            // ConditionalHeaders evaluates into a 304 on revalidation
            assertEquals("no-cache", root.headers[HttpHeaders.CacheControl])
            assertEquals("max-age=604800", asset.headers[HttpHeaders.CacheControl])
            val etag = root.headers[HttpHeaders.ETag]
            assertNotNull(etag, "index.html must carry an ETag")
            assertEquals(
                HttpStatusCode.NotModified,
                client.get("/") { header(HttpHeaders.IfNoneMatch, etag) }.status,
                "revalidating index.html must answer 304",
            )
        }
    }

    @Test
    fun `unknown paths stay 404 - no index fallback shadows them`() {
        testApplication {
            application {
                routing {
                    // an /api route coexisting with the static tailcard: the
                    // deeper match must win (see staticWebUi's KDoc)
                    get("/api/ping") { call.respondText("pong") }
                    staticWebUi()
                }
            }
            // no default("index.html"): an unknown path (including unknown
            // /api ones, which would otherwise be shadowed by a fallback)
            // resolves nothing and falls through to ktor's plain 404
            // ("/" is not unknown — it serves the index, covered above)
            listOf("/api/unknown", "/assets/missing.js", "/some/other/path").forEach { path ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.NotFound, response.status, "path: $path")
            }
            assertEquals(
                "pong",
                client.get("/api/ping").bodyAsText(),
                "the /api route must keep serving over the static tailcard",
            )
        }
    }

    @Test
    fun `an absent resource package answers 404 everywhere - the dev behavior`() {
        testApplication {
            application {
                routing {
                    // dev runs `./gradlew run` without the Docker-populated
                    // package: every web-UI request must degrade to a plain 404
                    staticWebUi("definitely-missing-package")
                }
            }
            listOf("/", "/index.html", "/assets/app.test.js").forEach { path ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.NotFound, response.status, "path: $path")
            }
        }
    }
}
