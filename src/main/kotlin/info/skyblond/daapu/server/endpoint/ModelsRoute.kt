package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.server.ChatRunService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.registerModelsEndpoints(service: ChatRunService) {
    get("/models") {
        call.respond(service.models())
    }
}