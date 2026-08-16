package info.skyblond.daapu.hand

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The hand-pi tool callback route: the hand POSTs each tool call here
 * mid-run, the brain looks up the in-flight run by
 * `runId` and executes the tool. Responses:
 *
 * - `200 {parts, isError}` — the tool executed (tool-level failures carry
 *   `isError=true` and are model-visible, not fatal);
 * - `200 {fatal: {message}}` — transport-level failure (MCP unreachable,
 *   unknown runId, capability mismatch on result attachments): the hand
 *   ends the run with `error{tool_transport}`;
 * - `401` — wrong/missing shared token.
 *
 * The route lives outside the `/api/chats` run handling: tool callbacks
 * are their own HTTP requests, possibly from a different connection than
 * the SSE run stream.
 */
fun Route.handToolCallback(service: HandCallbackService) {
    post("/hand/tool") {
        if (!service.verifyToken(call.request.headers["x-daapu-token"])) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val request = call.receive<HandToolCallbackRequest>()
        call.respond(service.executeToolCall(request))
    }
}
