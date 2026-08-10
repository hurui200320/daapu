package info.skyblond.daapu.agent.lc4j.executor

/**
 * A mid-stream SSE `{"error": ...}` chunk was detected after the stream
 * completed, but the chunk carries no numeric `code` (e.g., a string code or
 * none at all). Thrown by [Lc4jStreamingExecutor]'s error-chunk scan; the
 * run fails with a clear SSE `error` event. (The old koog client treated
 * code-less chunks as transient and retried them; retrying HTTP-level
 * failures is a TODO when the classification returns.)
 */
class MidStreamErrorChunkException(message: String) : Exception(message)
