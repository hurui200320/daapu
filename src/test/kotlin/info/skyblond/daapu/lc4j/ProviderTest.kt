package info.skyblond.daapu.lc4j

import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderTest {

    @Test
    fun `provider id must match the safe id charset`() {
        for (id in listOf("bifrost", "exa-1", "a_b", "0")) {
            assertTrue(
                BifrostProvider(id, "http://gateway.example/v1", "test-key").id == id,
                "id '$id' should be accepted"
            )
        }
    }

    @Test
    fun `provider id with invalid chars is rejected`() {
        for (id in listOf("", "Bad id", "a/b", "a.b", "a:b", "UPPER", "bifrost ")) {
            assertFailsWith<IllegalArgumentException>("id '$id' should be rejected") {
                BifrostProvider(id, "http://gateway.example/v1", "test-key")
            }
        }
    }
}
