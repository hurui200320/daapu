package info.skyblond.daapu.nats

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [StreamSeqDedup]: a fresh sequence is admitted, a repeated one is
 * rejected, and the bound evicts the oldest entry so a long-lived consumer
 * never grows memory without limit.
 */
class StreamSeqDedupTest {

    @Test
    fun firstSightingOfASequenceIsAdmitted() {
        val dedup = StreamSeqDedup(capacity = 4)
        assertTrue(dedup.shouldProcess(1L), "first sighting of seq=1 should be processed")
    }

    @Test
    fun redeliveryOfASeenSequenceIsSuppressed() {
        val dedup = StreamSeqDedup(capacity = 4)
        assertTrue(dedup.shouldProcess(1L))
        assertFalse(dedup.shouldProcess(1L), "redelivered seq=1 should be suppressed")
        assertFalse(dedup.shouldProcess(1L), "third sighting is still a duplicate")
    }

    @Test
    fun distinctSequencesAreAllAdmitted() {
        val dedup = StreamSeqDedup(capacity = 4)
        assertTrue(dedup.shouldProcess(1L))
        assertTrue(dedup.shouldProcess(2L))
        assertTrue(dedup.shouldProcess(3L))
        assertFalse(dedup.shouldProcess(1L))
        assertFalse(dedup.shouldProcess(2L))
        assertTrue(dedup.shouldProcess(4L))
    }

    @Test
    fun evictionIsFifoOldestIsForgottenAndReAdmitted() {
        val dedup = StreamSeqDedup(capacity = 2)
        assertTrue(dedup.shouldProcess(1L))
        assertTrue(dedup.shouldProcess(2L))
        // capacity reached; inserting 3 evicts the oldest (1) -> live set {2,3}
        assertTrue(dedup.shouldProcess(3L))
        assertFalse(dedup.shouldProcess(2L), "seq=2 still in window {2,3} stays suppressed")
        assertFalse(dedup.shouldProcess(3L), "seq=3 still in window {2,3} stays suppressed")
        // seq=1 has been forgotten, so it is admitted again. (Re-admitting 1
        // also evicts the new oldest (2), but the point here is that an evicted
        // sequence is no longer suppressed.)
        assertTrue(dedup.shouldProcess(1L), "evicted seq=1 should be re-admitted")
    }

    @Test
    fun capacityIsHeldExactly() {
        val dedup = StreamSeqDedup(capacity = 3)
        for (s in 1L..3L) assertTrue(dedup.shouldProcess(s))
        // One beyond capacity evicts the oldest; the live set stays at `capacity`.
        assertTrue(dedup.shouldProcess(4L)) // live set {2,3,4}
        assertFalse(dedup.shouldProcess(2L))
        assertFalse(dedup.shouldProcess(3L))
        assertFalse(dedup.shouldProcess(4L))
        assertTrue(dedup.shouldProcess(1L), "seq=1 evicted, re-admitted")
    }

    @Test
    fun forgetAllowsARedeliveryToBeReprocessed() {
        // Mirrors the lost-ack fix: after the envelope is acked/nacked the
        // sequence is forgotten, so a later redelivery is processed again
        // (at-least-once) instead of suppressed forever.
        val dedup = StreamSeqDedup(capacity = 4)
        assertTrue(dedup.shouldProcess(1L))
        dedup.forget(1L)
        assertTrue(dedup.shouldProcess(1L), "seq=1 forgotten on ack/nack -> reprocessed")
        assertFalse(dedup.shouldProcess(1L), "and a second redelivery is a duplicate again")
    }

    @Test
    fun forgetOnlyRemovesTheRequestedSequence() {
        val dedup = StreamSeqDedup(capacity = 4)
        assertTrue(dedup.shouldProcess(1L))
        assertTrue(dedup.shouldProcess(2L))
        dedup.forget(1L)
        assertTrue(dedup.shouldProcess(1L), "seq=1 was forgotten -> reprocessed")
        assertFalse(dedup.shouldProcess(2L), "seq=2 is unaffected and still suppressed")
    }

    @Test
    fun forgetOfAnUnknownSequenceIsANoop() {
        val dedup = StreamSeqDedup(capacity = 4)
        assertTrue(dedup.shouldProcess(1L))
        dedup.forget(99L)
        assertFalse(dedup.shouldProcess(1L), "forgetting an unknown seq leaves others intact")
    }

    @Test
    fun forgetThenInsertRespectsCapacity() {
        val dedup = StreamSeqDedup(capacity = 2)
        assertTrue(dedup.shouldProcess(1L)) // {1}
        assertTrue(dedup.shouldProcess(2L)) // {1,2}
        dedup.forget(1L)                    // {2}
        assertTrue(dedup.shouldProcess(3L)) // {2,3}
        assertFalse(dedup.shouldProcess(2L), "seq=2 still in set, suppressed")
        assertFalse(dedup.shouldProcess(3L), "seq=3 still in set, suppressed")
        assertTrue(dedup.shouldProcess(1L), "seq=1 forgotten, re-admitted (evicts 2) -> {3,1}")
    }
}
