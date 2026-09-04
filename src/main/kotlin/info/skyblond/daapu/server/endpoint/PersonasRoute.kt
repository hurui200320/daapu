package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.agent.persona.PersonaExportEntry
import info.skyblond.daapu.agent.persona.PersonaService
import info.skyblond.daapu.server.PersonaSaveRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Persona CRUD: `GET` lists the code default persona first, then the
 * `personas` rows; `POST` creates with a generated id; `PUT`/`DELETE` on
 * the reserved id 0 (the code default) are rejected with 400 (the default
 * persona lives in code and is read-only).
 *
 * Export/import speak the `PersonaExportEntry` array (see
 * `PersonaService.exportPersonas`/`importPersonas`): the export answers an
 * attachment named `personas.json`, the import takes the same shape back and
 * answers the created/skipped split. No route conflicts: GET and POST have
 * no `/{personaId}` siblings (only PUT/DELETE carry the id).
 *
 * The service's validation errors ([IllegalArgumentException]) are mapped
 * onto 400 here — the service package holds no ktor dependency, the routes
 * own the HTTP mapping.
 */
fun Route.registerPersonasEndpoints(service: PersonaService) {
    route("/personas") {
        get {
            call.respond(service.list())
        }
        // export every persona row as the transfer array (the code default is
        // excluded — see PersonaService.exportPersonas); an attachment, like
        // the chat export
        get("/export") {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "personas.json")
                    .toString(),
            )
            call.respond(service.exportPersonas())
        }
        // import the transfer array: entries matching an existing persona on
        // name + prompt + namespace set are skipped, the rest are created;
        // the first invalid entry fails the whole request with 400 and the
        // earlier creates stick (see PersonaService.importPersonas)
        post("/import") {
            val entries = call.receive<List<PersonaExportEntry>>()
            call.respond(personaValidation { service.importPersonas(entries) })
        }
        post {
            val request = call.receive<PersonaSaveRequest>()
            call.respond(
                HttpStatusCode.Created,
                personaValidation { service.create(request.name, request.systemPrompt, request.allowedNamespaces) }
            )
        }
        put("/{personaId}") {
            val id = call.personaIdParam()
            val request = call.receive<PersonaSaveRequest>()
            call.respond(
                personaValidation {
                    service.update(id, request.name, request.systemPrompt, request.allowedNamespaces)
                } ?: throw NotFoundException("Persona $id not found")
            )
        }
        delete("/{personaId}") {
            val id = call.personaIdParam()
            if (!personaValidation { service.delete(id) }) {
                throw NotFoundException("Persona $id not found")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The path param is the persona id itself: must parse as a number. */
private fun ApplicationCall.personaIdParam(): Long =
    parameters["personaId"]?.toLongOrNull()
        ?: throw BadRequestException("personaId is required and must be a numeric persona id")

private inline fun <T> personaValidation(block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    throw BadRequestException(e.message ?: "Invalid persona")
}
