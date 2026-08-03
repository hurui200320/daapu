package info.skyblond.daapu.llm

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

class FlagTool : Tool<FlagTool.Args, Int>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Int>(),
    name = "flag generator",
    description = "Impl for testing tool call feature, give a string, return a custom digest"
) {
    private val logger = KotlinLogging.logger {}

    // Arguments for the calculator tool
    @Serializable
    data class Args(
        @property:LLMDescription("Non-blank string of your choice")
        val input: String
    ) {
        init {
            require(input.isNotBlank()) { "input MUST NOT be blank" }
        }
    }

    // Function to add two digits
    override suspend fun execute(args: Args): Int {
        val result = args.input.hashCode()
        logger.info { "Called FlagTool: arg='${args.input}', result=$result" }
        return result
    }
}