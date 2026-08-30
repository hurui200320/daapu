package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.agent.persona.Persona
import info.skyblond.daapu.db.ELTM_VERSION_KEY
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.EltmEntities
import info.skyblond.daapu.db.EltmEntityAttributes
import info.skyblond.daapu.db.EltmNotes
import info.skyblond.daapu.db.EltmRelationships
import info.skyblond.daapu.db.MemoryMetaNumber
import info.skyblond.daapu.db.Personas
import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmRelationship
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The throwaway PostgreSQL the DB-backed tests run against, started by
 * testcontainers ONCE per test JVM (the singleton [container]): the
 * `pgvector/pgvector:pg18-trixie` image (the same tag `compose.yaml`'s
 * dev database uses), so the `V1__init.sql` `CREATE EXTENSION vector` works
 * out of the box. Docker must be available on the host; the container is
 * reaped when the JVM exits. The DB-backed test classes extend [DbTestBase],
 * which starts the container lazily and wipes every table before each test,
 * so tests are order-independent.
 */
object TestDb {

    /**
     * The container backing every test. `pgvector/pgvector` is a compatible
     * substitute for the official `postgres` image (same entrypoint and
     * behavior); no volume — the data is throwaway.
     */
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18-trixie")
                .asCompatibleSubstituteFor("postgres")
        ).apply { start() }
    }

    val url: String get() = container.jdbcUrl
    val user: String get() = container.username
    val password: String get() = container.password

    @Volatile
    private var initialized = false

    /**
     * Start the container (once per JVM) and connect Exposed with the
     * Flyway migrations (`initDatabase` builds a fresh Hikari pool; calling
     * it twice would leak one).
     *
     * On failure [initialized] stays false and a retry re-runs the whole
     * [initDatabase]: that is only clean BEFORE `Database.connect` (the
     * migrations are idempotent, the pool is closed on a migration failure
     * by `initDatabase` itself) — if the failure ever happened after the
     * connect, a retry would leak a second Exposed connection. Today
     * nothing runs after the connect, so the retry stays safe.
     */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                initDatabase(url, user, password)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "The testcontainers PostgreSQL failed to start — is Docker available?",
                    e,
                )
            }
            initialized = true
        }
    }

    /**
     * Wipe every table and restore the pristine `V1__init.sql` state (the
     * `eltm_version` counter row back at 0, every BIGSERIAL sequence back
     * at 1): the per-test isolation point. `TRUNCATE ... RESTART IDENTITY
     * CASCADE` handles FKs and sequences in one statement.
     *
     * KEEP THE TABLE LIST IN SYNC with `V1__init.sql`: a future migration
     * adding a table must add it here, or that table's rows silently leak
     * between tests (nothing fails when the list drifts).
     */
    fun resetAll() {
        init()
        runBlocking {
            withTransaction {
                exec(
                    "TRUNCATE chats, personas, memory_meta_number, eltm_entities, " +
                            "eltm_entity_attributes, eltm_relationships, eltm_notes " +
                            "RESTART IDENTITY CASCADE"
                )
                // the migration's seed row, restored through the table mapping
                // (no string SQL for the values)
                MemoryMetaNumber.insert {
                    it[MemoryMetaNumber.key] = ELTM_VERSION_KEY
                    it[MemoryMetaNumber.value] = 0L
                }
            }
        }
    }

    /**
     * Insert a `chats` row directly (a fixture, NOT the store's validation
     * path): tests seed arbitrary histories — including ones that would
     * fail [info.skyblond.daapu.agent.chat.ChatCodec.validateChat] on the
     * store path — without losing the round trip through the JSON column.
     */
    fun seedChatRow(
        chatId: String,
        title: String = DEFAULT_CHAT_TITLE,
        messages: List<ChatMessage> = emptyList(),
        eltmVersion: String = "",
        personaId: Long = DEFAULT_PERSONA_ID,
    ) {
        init()
        runBlocking {
            withTransaction {
                Chats.insert {
                    it[Chats.id] = chatId
                    it[Chats.title] = title
                    it[Chats.chatJson] = ChatCodec.encodeChat(messages)
                    it[Chats.eltmVersion] = eltmVersion
                    it[Chats.personaId] = personaId
                }
            }
        }
    }

    /**
     * Insert a `personas` row and return it with its DB-assigned id (the
     * BIGSERIAL identity — tests capture the id instead of hardcoding it,
     * and [resetAll] keeps the sequence deterministic across tests).
     */
    suspend fun seedPersonaRow(
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona {
        init()
        return withTransaction {
            val id = Personas.insert { row ->
                row[Personas.name] = name
                row[Personas.systemPrompt] = systemPrompt
                row[Personas.allowedNamespaces] = Json.encodeToString(allowedNamespaces)
            } get Personas.id
            Persona(id, name, systemPrompt, allowedNamespaces)
        }
    }

    // ------------------------------------------------------------------
    // raw ELTM reads (test assertions over rows the services wrote)
    // ------------------------------------------------------------------

    suspend fun allEltmEntities(): List<EltmEntity> = withTransaction {
        EltmEntities.selectAll().map { row ->
            EltmEntity(row[EltmEntities.id], row[EltmEntities.canonicalName], row[EltmEntities.category])
        }
    }

    suspend fun allEltmRelationships(): List<EltmRelationship> = withTransaction {
        EltmRelationships.selectAll().map { row ->
            EltmRelationship(
                row[EltmRelationships.id], row[EltmRelationships.srcId],
                row[EltmRelationships.dstId], row[EltmRelationships.verb], row[EltmRelationships.valid],
            )
        }
    }

    suspend fun allEltmNotes(): List<EltmNote> = withTransaction {
        EltmNotes.selectAll().map { row ->
            EltmNote(
                row[EltmNotes.id], row[EltmNotes.entityId], row[EltmNotes.relationshipId],
                row[EltmNotes.eventDate], row[EltmNotes.note],
                // the column default fills created_at; the read map keeps it
                // as the DB wrote it
                row[EltmNotes.createdAt],
            )
        }
    }
}
