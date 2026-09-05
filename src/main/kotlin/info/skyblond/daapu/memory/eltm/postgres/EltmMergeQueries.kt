package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.AttributeFoldPlan
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Ambient-transaction SQL for the entity merge ([PostgresEltmService.mergeEntities])
 * over the `eltm_entities` / `eltm_entity_attributes` / `eltm_relationships`
 * / `eltm_notes` tables (`V1__init.sql`). The merge's writes span all four
 * tables, so unlike the per-table query files this one owns ONE operation,
 * not one table: the read/lock/plan/embed decisions stay in the service —
 * this file executes the fold-and-delete writes. Every function here is
 * only ever called inside `withTransaction` — by the service or by the
 * other query files in this package.
 */

/**
 * Execute the writes of an entity merge for an already-locked,
 * already-planned pair ([PostgresEltmService.mergeEntities] holds both
 * rows FOR UPDATE and planned the attribute fold): re-point the loser's
 * relationships per the fold decision tree, re-point its diary notes to
 * the winner, fold its attributes per [foldPlan] (the colliding rows are
 * dropped: re-pointing them would overwrite the winner's value on the
 * composite PK; the foldable rows re-point to the winner), store the
 * winner's re-embedded vector when [winnerEmbedding] is given, then
 * delete the loser row (the cascade never fires: no note references the
 * loser anymore). The write counter bump is the caller's — one per
 * transactional merge. Ambient transaction.
 */
internal fun executeEntityMerge(
    winnerId: Long,
    loserId: Long,
    foldPlan: AttributeFoldPlan,
    winnerEmbedding: List<Float>?,
) {
    val loserRels = EltmRelationships.selectAll().where {
        (EltmRelationships.srcId eq loserId) or (EltmRelationships.dstId eq loserId)
    }.toList()
    // The relationship-fold decision tree below (self-loop → invalidate,
    // triple collision → fold duplicate away, else re-point) has NO
    // shared pure planner — unlike the attribute fold (planned by
    // [planAttributeFold] in the service before this call) — and the
    // loop interleaves DB lookups with its own mutations (earlier
    // iterations create/delete rows the survivor lookup must see), so
    // planner extraction needs care to simulate that in-loop state.
    // The tree's behavior is pinned by PostgresEltmServiceTest's
    // mergeEntities tests.
    for (rel in loserRels) {
        val newSrc =
            if (rel[EltmRelationships.srcId] == loserId) winnerId
            else rel[EltmRelationships.srcId]
        val newDst =
            if (rel[EltmRelationships.dstId] == loserId) winnerId
            else rel[EltmRelationships.dstId]

        val survivor = findRelationshipByTriple(newSrc, rel[EltmRelationships.verb], newDst)
        when {
            // the re-pointed edge would become a self-loop (winner—winner,
            // from winner—loser, loser—winner or loser—loser): invalidate
            // instead of re-pointing — a self-loop is never meaningful.
            // A twin self-loop row may already exist (another loser edge
            // collapsed onto the same triple first): fold into it instead
            // of violating the unique index
            newSrc == newDst -> if (survivor != null) {
                EltmNotes.update({ EltmNotes.relationshipId eq rel[EltmRelationships.id] }) {
                    it[EltmNotes.relationshipId] = survivor[EltmRelationships.id]
                }
                EltmRelationships.deleteWhere {
                    EltmRelationships.id eq rel[EltmRelationships.id]
                }
            } else {
                EltmRelationships.update({ EltmRelationships.id eq rel[EltmRelationships.id] }) {
                    it[EltmRelationships.srcId] = newSrc
                    it[EltmRelationships.dstId] = newDst
                    it[EltmRelationships.valid] = false
                }
            }

            survivor != null && survivor[EltmRelationships.id] != rel[EltmRelationships.id] -> {
                // collides with an existing row of the same triple
                // (valid or not): re-point the duplicate's diary notes
                // to the survivor, fold the validity (the survivor
                // holds the edge if either row held it), and only
                // THEN delete the duplicate row (the ON DELETE
                // CASCADE must never destroy diary notes)
                EltmNotes.update({ EltmNotes.relationshipId eq rel[EltmRelationships.id] }) {
                    it[EltmNotes.relationshipId] = survivor[EltmRelationships.id]
                }
                if (rel[EltmRelationships.valid] && !survivor[EltmRelationships.valid]) {
                    EltmRelationships.update({ EltmRelationships.id eq survivor[EltmRelationships.id] }) {
                        it[EltmRelationships.valid] = true
                    }
                }
                EltmRelationships.deleteWhere {
                    EltmRelationships.id eq rel[EltmRelationships.id]
                }
            }

            // no collision (or the survivor IS this row): re-point
            else -> EltmRelationships.update({ EltmRelationships.id eq rel[EltmRelationships.id] }) {
                it[EltmRelationships.srcId] = newSrc
                it[EltmRelationships.dstId] = newDst
            }
        }
    }

    EltmNotes.update({ EltmNotes.entityId eq loserId }) {
        it[EltmNotes.entityId] = winnerId
    }
    val droppedKeys = foldPlan.droppedKeys.toList()
    if (droppedKeys.isNotEmpty()) {
        EltmEntityAttributes.deleteWhere {
            (EltmEntityAttributes.entityId eq loserId) and
                (EltmEntityAttributes.key inList droppedKeys)
        }
    }
    val foldableKeys = foldPlan.foldableKeys.toList()
    if (foldableKeys.isNotEmpty()) {
        EltmEntityAttributes.update({
            (EltmEntityAttributes.entityId eq loserId) and
                (EltmEntityAttributes.key inList foldableKeys)
        }) {
            it[EltmEntityAttributes.entityId] = winnerId
        }
    }
    if (winnerEmbedding != null) {
        EltmEntities.update({ EltmEntities.id eq winnerId }) {
            it[EltmEntities.embedding] = winnerEmbedding
        }
    }
    EltmEntities.deleteWhere { EltmEntities.id eq loserId }
}
