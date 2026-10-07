package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.AttributeFoldPlan
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Ambient-transaction SQL for the entity merge ([PostgresEltmService.mergeEntities])
 * over the `eltm_entities` / `eltm_entity_attributes` / `eltm_relationships`
 * / `eltm_notes` tables (`V1__init.sql`). The merge's writes span all four
 * tables, so unlike the per-table query files this one owns ONE operation,
 * not one table: the read/plan/embed decisions stay in the service —
 * this file takes the fold-set row locks and executes the
 * fold-and-delete writes. Every function here is
 * only ever called inside `withTransaction` — by the service or by the
 * other query files in this package.
 */

/**
 * Execute the writes of an entity merge for an already-locked,
 * already-planned pair ([PostgresEltmService.mergeEntities] holds both
 * rows FOR UPDATE and planned the attribute fold; this function locks
 * the loser's relationship rows FOR UPDATE as its first step — see the
 * lock comment below for why the entity locks alone do not close the
 * fold's race): re-point the loser's
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
    // Lock the whole fold set FOR UPDATE before touching any note — the
    // caller's two entity row locks do NOT close the fold's delete race:
    // a note insert on a relationship row takes a FOR KEY SHARE lock via
    // its FK check, which conflicts with a FOR UPDATE-level lock only.
    // Unlocked, a racing note insert on a duplicate commits between the
    // duplicate's note move and its delete, and the delete's ON DELETE
    // CASCADE destroys that committed note — silent data loss. Locked,
    // the racing insert either commits FIRST (its note is then visible
    // to the note move and rides to the survivor) or blocks until the
    // merge commits and then fails its FK re-check against the deleted
    // row — a loud IllegalArgumentException on the writer's path
    // (attachNotesToRelationship maps the FK violation), never a
    // silently lost note.
    //
    // The ascending id order is the deterministic lock order of this
    // one acquisition: two merges on DISJOINT entity pairs can still
    // share an edge between their fold sets (an edge spanning both
    // losers), and locking both sets in one global order keeps that
    // pair deadlock-free — merges sharing an entity already serialize
    // on its FOR UPDATE lock, and every writer ends its transaction at
    // the write counter, so the global order entity rows →
    // relationship rows → counter holds everywhere. The residual: the
    // loop's own writes can still wait on rows OUTSIDE the fold set — a
    // notes move or validity flip targets the survivor row, and a
    // re-point's FK trigger takes FOR KEY SHARE on its new endpoints —
    // so two merges on four distinct entities can still form a cycle
    // there. That residual is not a corruption risk: Postgres detects
    // the deadlock, aborts one transaction loudly, and the
    // withTransaction retry (db/Database.kt) re-runs the whole merge
    // on committed state. READ COMMITTED re-checks a row after its
    // lock wait, so each row read here is the latest committed version
    // and a row another merge fold-deleted meanwhile simply drops out
    // of the set. The stable order also pins the loop's iteration
    // order below.
    val loserRels = EltmRelationships.selectAll().where {
        (EltmRelationships.srcId eq loserId) or (EltmRelationships.dstId eq loserId)
    }
        .orderBy(EltmRelationships.id to SortOrder.ASC)
        .forUpdate(ForUpdateOption.ForUpdate)
        .toList()
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

    // the entity-note re-point needs no lock of its own: the loser
    // entity row's FOR UPDATE (held by the caller) already blocks a
    // racing entity-note insert's FK key-share until the merge commits —
    // and the merge re-points every note visible at that point, so the
    // insert either rides along or fails the FK re-check loud, same
    // contract as the relationship fold above
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
