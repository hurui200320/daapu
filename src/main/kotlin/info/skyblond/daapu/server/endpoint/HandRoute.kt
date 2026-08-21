package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandToolCallbackRequest
import info.skyblond.daapu.hand.HandToolListResponse
import info.skyblond.daapu.hand.HandToolSpec
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.registerHandEndpoints(service: HandCallbackService) {
    route("/hand") {
        handToolList(service)
        handToolCallback(service)
    }
}

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
private fun Route.handToolCallback(service: HandCallbackService) {
    post("/tool") {
        if (!service.verifyToken(call.request.headers["x-daapu-token"])) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val request = call.receive<HandToolCallbackRequest>()
        call.respond(service.executeToolCall(request))
    }
}

/**
 * The hand-pi tool-listing route: the hand GETs the in-flight run's
 * current tool advertisements here BEFORE every LLM request, so the model
 * always sees the provider's latest tool set (the run request carries no
 * static tools anymore). Responses:
 *
 * - `200 {tools: [...]}` — the provider's current advertisements
 *   (`hand/HandDtos.kt` `HandToolListResponse`);
 * - `400` — missing `runId`;
 * - `401` — wrong/missing shared token;
 * - `404` — the runId is not (or no longer) registered;
 * - `500` — the provider failed to list its tools (e.g. an unreachable
 *   MCP server): the hand ends the run with `error{tool_transport}`.
 */
private fun Route.handToolList(service: HandCallbackService) {
    get("/tools") {
        if (!service.verifyToken(call.request.headers["x-daapu-token"])) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        val runId = call.request.queryParameters["runId"]?.trim().orEmpty()
        if (runId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val specs = service.listTools(runId)
            ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
        call.respond(
            HandToolListResponse(
                specs.map { HandToolSpec(it.name, it.description, it.schema) }
            )
        )
    }
}
