package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.server.MemoryDto.Companion.toDto
import info.skyblond.daapu.server.MemoryWriteRequest
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.registerSstmEndpoints(sstmService: SstmService) {
    route("/sstm") {
        get {
            call.respond(
                sstmService.listMemories().memories.map { it.toDto() }
            )
        }
        post {
            val request = call.receive<MemoryWriteRequest>()
            val content = request.content.trim()
            if (content.isEmpty()) throw BadRequestException("Memory content is empty")
            call.respond(
                HttpStatusCode.Created,
                sstmService.createMemory(content).toDto()
            )
        }
        put("/{memoryId}") {
            val id = call.longParam("memoryId")
            val request = call.receive<MemoryWriteRequest>()
            val content = request.content.trim()
            if (content.isEmpty()) throw BadRequestException("Memory content is empty")
            call.respond(
                sstmService.updateMemory(id, content)?.toDto()
                    ?: throw NotFoundException("Memory $id not found")
            )
        }
        delete("/{memoryId}") {
            val id = call.longParam("memoryId")
            if (!sstmService.deleteMemory(id)) throw NotFoundException("Memory $id not found")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
