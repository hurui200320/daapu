package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.server.ModelInfo
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.registerModelsEndpoints(catalog: ModelCatalog) {
    get("/models") {
        call.respond(
            catalog.models.map {
                ModelInfo(
                    id = it.id,
                    vision = it.supports(LLMCapability.Input.Vision.Image),
                    contextLength = it.contextLength,
                    maxOutputTokens = it.maxOutputTokens,
                )
            }
        )
    }
}
