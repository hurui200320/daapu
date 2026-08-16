package info.skyblond.daapu.testutil

import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandService

/**
 * Wrap a fake [HandClient] in the production [HandService] wiring: the
 * runId generation, the in-flight callback registry, and the callback URL.
 * The services under test receive the [HandService]; tests keep the
 * [HandClient] reference for request/response assertions.
 */
fun testHandService(
    hand: HandClient,
    toolCallbackUrl: String = "http://127.0.0.1:9/api/hand/tool",
): HandService = HandService(hand, HandCallbackService("test-token"), toolCallbackUrl)
