package info.skyblond.daapu.agent.oneshot.eltm

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.testEltmWriterService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.test.*

class EltmToolProviderTest {

    private fun toolCall(id: String, name: String, arguments: JsonObject) =
        ToolCallRequest(id = id, name = name, args = arguments)

    private fun textOf(result: ChatMessagePart.ToolResult) =
        (result.parts.single() as ChatMessagePart.Text).text

    @Test
    fun `the writer advertises the thirteen eltm tools in order with integer ids`() {
        val provider = EltmToolProvider(FakeEltmService())
        val specs = runBlocking { provider.specifications() }
        assertEquals(
            listOf(
                "search_entities",
                "get_relationships",
                "get_entity_notes",
                "get_relationship_notes",
                "search_notes",
                "create_entity",
                "refine_entity",
                "create_relationship",
                "merge_entities",
                "add_entity_note",
                "add_relationship_note",
                "set_entity_attribute",
                "delete_entity_attribute",
            ),
            specs.map { it.name },
        )
        specs.forEach { spec ->
            val properties = spec.schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            properties.forEach { (name, schema) ->
                if (name.contains("_id") || name == "id" || name == "limit" || name == "offset") {
                    assertEquals(
                        "integer", schema.jsonObject["type"]?.jsonPrimitive?.content,
                        "numeric argument '$name' of '${spec.name}' must use an integer schema"
                    )
                }
            }
        }
    }

    @Test
    fun `the read-only provider advertises exactly the five read tools`() {
        val provider = EltmToolProvider(FakeEltmService(), readOnly = true)
        val specs = runBlocking { provider.specifications() }
        assertEquals(
            listOf(
                "search_entities",
                "get_relationships",
                "get_entity_notes",
                "get_relationship_notes",
                "search_notes",
            ),
            specs.map { it.name },
        )
    }

    @Test
    fun `the read-only provider rejects write tools`() = runBlocking {
        val provider = EltmToolProvider(FakeEltmService(), readOnly = true)
        val result = provider.execute(
            toolCall("c1", "create_entity", buildJsonObject { put("name", "Alice") }),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("not available in read-only mode"), textOf(result))

        val attrWrite = provider.execute(
            toolCall("c2", "set_entity_attribute", buildJsonObject {
                put("entity_id", 1)
                put("key", "model")
                put("value", "Paperwhite 6")
            }),
        )
        assertTrue(attrWrite.isError)
        assertTrue(textOf(attrWrite).contains("not available in read-only mode"), textOf(attrWrite))

        val refineWrite = provider.execute(
            toolCall("c3", "refine_entity", buildJsonObject {
                put("entity_id", 1)
                put("new_name", "Alice")
            }),
        )
        assertTrue(refineWrite.isError)
        assertTrue(textOf(refineWrite).contains("not available in read-only mode"), textOf(refineWrite))
    }

    @Test
    fun `search_entities returns the hits with their similarity`() = runBlocking {
        val eltm = FakeEltmService()
        eltm.createEntity("Alice", "person")
        eltm.createEntity("Bob", "person")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "search_entities", buildJsonObject { put("query", "ali") })
        )
        assertFalse(result.isError)
        val text = textOf(result)
        assertTrue(text.contains("alice"), text)
        assertFalse(text.contains("bob"), "substring match on the canonical name only")
        assertTrue(text.contains("similarity"), text)
    }

    @Test
    fun `get_relationships returns both directions with endpoint names and the latest note`() =
        runBlocking {
            val eltm = FakeEltmService()
            val alice = eltm.createEntity("Alice", "person").entity
            val bob = eltm.createEntity("Bob", "person").entity
            val rel = eltm.createRelationship(alice.id, bob.id, "colleague of")
            eltm.attachNoteToRelationship(rel.id, LocalDate.parse("2026-08-18"), "met at the conference")
            val provider = EltmToolProvider(eltm)

            val result = provider.execute(
                toolCall("c1", "get_relationships", buildJsonObject { put("entity_id", alice.id) }),
            )
            assertFalse(result.isError)
            val text = textOf(result)
            assertTrue(text.contains("alice") && text.contains("bob"), text)
            assertTrue(text.contains("colleague_of"), "the normalized verb renders: $text")
            assertTrue(text.contains("met at the conference"), "the latest note is inline: $text")
        }

    @Test
    fun `get_relationships errors on a missing entity`() = runBlocking {
        val provider = EltmToolProvider(FakeEltmService())
        val result = provider.execute(
            toolCall("c1", "get_relationships", buildJsonObject { put("entity_id", 999) }),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("999"), textOf(result))
    }

    @Test
    fun `get_entity_notes pages latest-first`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-01"), "early")
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-10"), "late")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall(
                "c1",
                "get_entity_notes",
                buildJsonObject {
                    put("entity_id", alice.id)
                    put("limit", 1)
                },
            ),
        )
        val text = textOf(result)
        assertTrue(text.contains("late"), "newest event first: $text")
        assertFalse(text.contains("early"))

        val missingId = provider.execute(
            toolCall("c2", "get_entity_notes", buildJsonObject { put("entity_id", 999) }),
        )
        assertTrue(missingId.isError)
        assertTrue(textOf(missingId).contains("999"), textOf(missingId))
    }

    @Test
    fun `get_relationship_notes pages latest-first`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val bob = eltm.createEntity("Bob", "person").entity
        val rel = eltm.createRelationship(alice.id, bob.id, "works_at")
        eltm.attachNoteToRelationship(rel.id, LocalDate.parse("2026-08-01"), "early")
        eltm.attachNoteToRelationship(rel.id, LocalDate.parse("2026-08-10"), "late")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall(
                "c1",
                "get_relationship_notes",
                buildJsonObject {
                    put("relationship_id", rel.id)
                    put("limit", 1)
                },
            ),
        )
        val text = textOf(result)
        assertTrue(text.contains("late"), "newest event first: $text")
        assertFalse(text.contains("early"))

        val missingId = provider.execute(
            toolCall("c2", "get_relationship_notes", buildJsonObject { put("relationship_id", 999) }),
        )
        assertTrue(missingId.isError)
        assertTrue(textOf(missingId).contains("999"), textOf(missingId))
    }

    @Test
    fun `note tools narrow by an inclusive date range and page with offset`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-01"), "early")
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-05"), "middle")
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-10"), "late")
        val provider = EltmToolProvider(eltm)

        val narrowed = provider.execute(
            toolCall(
                "c1",
                "get_entity_notes",
                buildJsonObject {
                    put("entity_id", alice.id)
                    put("from", "2026-08-01")
                    put("to", "2026-08-05")
                },
            ),
        )
        val text = textOf(narrowed)
        assertTrue(text.contains("early") && text.contains("middle"), text)
        assertFalse(text.contains("late"), "the range is inclusive, not open-ended: $text")

        val paged = provider.execute(
            toolCall(
                "c2",
                "get_entity_notes",
                buildJsonObject {
                    put("entity_id", alice.id)
                    put("limit", 1)
                    put("offset", 1)
                },
            ),
        )
        val pagedText = textOf(paged)
        assertTrue(pagedText.contains("middle"), "offset skips the newest note: $pagedText")
        assertFalse(pagedText.contains("late"))
    }

    @Test
    fun `note tools error on malformed dates`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val bob = eltm.createEntity("Bob", "person").entity
        val rel = eltm.createRelationship(alice.id, bob.id, "works_at")
        val provider = EltmToolProvider(eltm)

        val badFrom = provider.execute(
            toolCall(
                "c1",
                "get_entity_notes",
                buildJsonObject {
                    put("entity_id", alice.id)
                    put("from", "yesterday")
                },
            ),
        )
        assertTrue(badFrom.isError, "a malformed from must not be silently ignored")
        assertTrue(textOf(badFrom).contains("YYYY-MM-DD"), textOf(badFrom))

        val badTo = provider.execute(
            toolCall(
                "c2",
                "get_relationship_notes",
                buildJsonObject {
                    put("relationship_id", rel.id)
                    put("to", "soon")
                },
            ),
        )
        assertTrue(badTo.isError)
        assertTrue(textOf(badTo).contains("YYYY-MM-DD"), textOf(badTo))
    }

    @Test
    fun `note tools error when from is after to`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall(
                "c1",
                "get_entity_notes",
                buildJsonObject {
                    put("entity_id", alice.id)
                    put("from", "2026-08-10")
                    put("to", "2026-08-01")
                },
            ),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("from must not be after to"), textOf(result))
    }

    @Test
    fun `search_notes returns matching notes newest-first and validates the subject`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-01"), "likes coffee")
        eltm.attachNoteToEntity(alice.id, LocalDate.parse("2026-08-10"), "likes tea")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "search_notes", buildJsonObject { put("query", "coffee") }),
        )
        assertFalse(result.isError)
        val text = textOf(result)
        assertTrue(text.contains("likes coffee"), text)
        assertFalse(text.contains("likes tea"), "only the matching note renders: $text")

        val none = provider.execute(
            toolCall("c2", "search_notes", buildJsonObject { put("query", "nonexistent") }),
        )
        assertFalse(none.isError)
        assertEquals("No matching notes.", textOf(none))

        val bothSubjects = provider.execute(
            toolCall(
                "c3",
                "search_notes",
                buildJsonObject {
                    put("query", "coffee")
                    put("entity_id", alice.id)
                    put("relationship_id", 1)
                },
            ),
        )
        assertTrue(bothSubjects.isError)
        assertTrue(textOf(bothSubjects).contains("at most one subject"), textOf(bothSubjects))
    }

    @Test
    fun `search_notes errors on a missing subject and malformed dates`() = runBlocking {
        val provider = EltmToolProvider(FakeEltmService())

        val missingEntity = provider.execute(
            toolCall(
                "c1",
                "search_notes",
                buildJsonObject {
                    put("query", "coffee")
                    put("entity_id", 999)
                },
            ),
        )
        assertTrue(missingEntity.isError)
        assertTrue(textOf(missingEntity).contains("999"), textOf(missingEntity))

        val badDate = provider.execute(
            toolCall(
                "c2",
                "search_notes",
                buildJsonObject {
                    put("query", "coffee")
                    put("from", "last week")
                },
            ),
        )
        assertTrue(badDate.isError)
        assertTrue(textOf(badDate).contains("YYYY-MM-DD"), textOf(badDate))
    }

    @Test
    fun `create_entity creates or fetches the entity and reports near matches`() = runBlocking {
        val eltm = FakeEltmService()
        val provider = EltmToolProvider(eltm)

        val created = provider.execute(
            toolCall("c1", "create_entity", buildJsonObject { put("name", "  Alice  ") }),
        )
        assertFalse(created.isError)
        val text = textOf(created)
        assertTrue(text.contains("\"alice\" (general)"), "canonicalized name and category: $text")

        // the same name+category again fetches the row instead of inserting
        val again = provider.execute(
            toolCall("c2", "create_entity", buildJsonObject { put("name", "Alice") }),
        )
        assertFalse(again.isError)
        assertEquals(1, eltm.entities.size, "an exact match is a fetch, not a new row")
        assertEquals(1, eltm.writeVersion, "an exact match touches nothing")
        val againText = textOf(again)
        assertTrue(againText.contains("notes 0") && againText.contains("relations 0"), againText)
        // a different category disambiguates: it is a NEW entity
        val other = provider.execute(
            toolCall("c3", "create_entity", buildJsonObject {
                put("name", "Alice")
                put("category", "person")
            }),
        )
        assertFalse(other.isError)
        assertEquals(2, eltm.entities.size, "category separates homonyms")
        assertEquals(2, eltm.writeVersion, "only real inserts bump the version")
    }

    @Test
    fun `refine_entity renames the entity in place keeping its id and content`() = runBlocking {
        val eltm = FakeEltmService()
        val friend = eltm.createEntity("friend", "person").entity
        eltm.attachNoteToEntity(friend.id, LocalDate.parse("2026-08-01"), "met at a party")
        val bob = eltm.createEntity("Bob", "person").entity
        eltm.createRelationship(friend.id, bob.id, "colleague_of")
        eltm.setEntityAttribute(friend.id, "nickname", "buddy")
        val versionBefore = eltm.writeVersion
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_name", "  Alice Smith  ")
            }),
        )
        assertFalse(result.isError, textOf(result))
        val text = textOf(result)
        assertTrue(text.contains("\"alice smith\" (person)"), "the refined identity renders: $text")
        assertTrue(text.contains("notes 1") && text.contains("relations 1"), text)
        assertTrue(text.contains("nickname: buddy"), "attributes survive the refine: $text")
        assertEquals("alice smith", eltm.entities[friend.id]!!.canonicalName)
        assertEquals("person", eltm.entities[friend.id]!!.category)
        assertEquals(versionBefore + 1, eltm.writeVersion, "a real refine bumps the version")
        assertEquals(1, eltm.notes.size, "the diary note stays attached")
        assertTrue(
            eltm.relationships.values.single().srcId == friend.id,
            "relationships keep pointing at the same id"
        )
        assertNull(
            eltm.entities.values.firstOrNull { it.canonicalName == "friend" },
            "the placeholder name is gone"
        )
    }

    @Test
    fun `refine_entity changes the category optionally and canonicalizes`() = runBlocking {
        val eltm = FakeEltmService()
        val friend = eltm.createEntity("friend", "general").entity
        val provider = EltmToolProvider(eltm)

        val keepCat = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_name", "alice")
            }),
        )
        assertFalse(keepCat.isError, textOf(keepCat))
        assertEquals(
            "general", eltm.entities[friend.id]!!.category,
            "an omitted category keeps the current one"
        )

        val changeCat = provider.execute(
            toolCall("c2", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_name", "Alice")
                put("new_category", "  Person  ")
            }),
        )
        assertFalse(changeCat.isError, textOf(changeCat))
        assertTrue(
            textOf(changeCat).contains("\"alice\" (person)"),
            "name and category canonicalize: ${textOf(changeCat)}"
        )
    }

    @Test
    fun `refine_entity is a no-op when nothing changes`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val versionBefore = eltm.writeVersion
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", alice.id)
                put("new_name", "alice")
                put("new_category", "PERSON")
            }),
        )
        assertFalse(result.isError, textOf(result))
        assertTrue(textOf(result).contains("\"alice\" (person)"), textOf(result))
        assertEquals(versionBefore, eltm.writeVersion, "a no-op refine touches nothing")
    }

    @Test
    fun `refine_entity fails fast on a missing entity and an empty call`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val provider = EltmToolProvider(eltm)

        val missing = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", 999)
                put("new_name", "Bob")
            }),
        )
        assertTrue(missing.isError)
        assertTrue(textOf(missing).contains("999"), textOf(missing))

        // neither new_name nor new_category: a degenerate call is an error,
        // not a silent no-op
        val empty = provider.execute(
            toolCall("c2", "refine_entity", buildJsonObject {
                put("entity_id", alice.id)
            }),
        )
        assertTrue(empty.isError)
        assertTrue(textOf(empty).contains("new_name or new_category"), textOf(empty))
        assertEquals("alice", eltm.entities[alice.id]!!.canonicalName, "nothing mutates")
        assertEquals("person", eltm.entities[alice.id]!!.category)

        // a blank new_name alone is the same degenerate call
        val blank = provider.execute(
            toolCall("c3", "refine_entity", buildJsonObject {
                put("entity_id", alice.id)
                put("new_name", "   ")
            }),
        )
        assertTrue(blank.isError)
        assertTrue(textOf(blank).contains("new_name or new_category"), textOf(blank))
    }

    @Test
    fun `refine_entity can change only the category keeping the name`() = runBlocking {
        val eltm = FakeEltmService()
        val friend = eltm.createEntity("friend", "general").entity
        val versionBefore = eltm.writeVersion
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_category", "  Person  ")
            }),
        )
        assertFalse(result.isError, textOf(result))
        assertTrue(
            textOf(result).contains("\"friend\" (person)"),
            "the name is untouched and the category renders: ${textOf(result)}"
        )
        assertEquals("friend", eltm.entities[friend.id]!!.canonicalName, "the name is kept")
        assertEquals("person", eltm.entities[friend.id]!!.category)
        assertEquals(versionBefore + 1, eltm.writeVersion, "a real category change bumps")
    }

    @Test
    fun `refine_entity errors when another entity holds the target name`() = runBlocking {
        val eltm = FakeEltmService()
        val friend = eltm.createEntity("friend", "person").entity
        val alice = eltm.createEntity("Alice", "person").entity
        val versionBefore = eltm.writeVersion
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_name", "Alice")
            }),
        )
        assertTrue(result.isError)
        val text = textOf(result)
        assertTrue(text.contains(alice.id.toString()), "the error names the existing entity: $text")
        assertTrue(text.contains("merge"), "the model is told to merge instead: $text")
        assertEquals("friend", eltm.entities[friend.id]!!.canonicalName, "nothing changed")
        assertEquals(versionBefore, eltm.writeVersion, "a collision bumps nothing")
    }

    @Test
    fun `refine_entity fails on an embedding failure without mutating`() = runBlocking {
        val eltm = FakeEltmService()
        val friend = eltm.createEntity("friend", "person").entity
        val versionBefore = eltm.writeVersion
        eltm.embedFailure = EmbeddingException("upstream", "gateway timeout")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_name", "Alice")
            }),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("embedding failed"), textOf(result))
        assertEquals("friend", eltm.entities[friend.id]!!.canonicalName)
        assertEquals(versionBefore, eltm.writeVersion, "a failed embed leaves the store untouched")

        // a too-large error tells the model to shorten the name or delete
        // an attribute: a refine re-embeds the name+category+attributes
        eltm.embedFailure = EmbeddingException(
            "invalid_request", "input too large for the embedding model"
        )
        val tooLarge = provider.execute(
            toolCall("c2", "refine_entity", buildJsonObject {
                put("entity_id", friend.id)
                put("new_name", "a very long name")
            }),
        )
        assertTrue(tooLarge.isError)
        val text = textOf(tooLarge)
        assertTrue(text.contains("shorten the name or delete an attribute"), text)
        assertFalse(
            text.contains("split it into several smaller notes"),
            "a name cannot be split, it must be shortened: $text"
        )
        assertEquals("friend", eltm.entities[friend.id]!!.canonicalName)
    }

    @Test
    fun `create_relationship inserts and fetches existing rows`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val bob = eltm.createEntity("Bob", "person").entity
        val provider = EltmToolProvider(eltm)

        val created = provider.execute(
            toolCall("c1", "create_relationship", buildJsonObject {
                put("src_id", alice.id)
                put("dst_id", bob.id)
                put("verb", "works at")
            }),
        )
        assertFalse(created.isError)
        assertTrue(textOf(created).contains("works_at"), textOf(created))
        assertTrue(textOf(created).contains("alice"), "the endpoint names render: ${textOf(created)}")
        val versionAfterCreate = eltm.writeVersion

        // re-creating an active triple fetches the row instead of inserting
        val fetched = provider.execute(
            toolCall("c2", "create_relationship", buildJsonObject {
                put("src_id", alice.id)
                put("dst_id", bob.id)
                put("verb", "works at")
            }),
        )
        assertFalse(fetched.isError, "a fetch is a success: ${textOf(fetched)}")
        assertEquals(1, eltm.relationships.size, "an active triple is fetched, not duplicated")
        assertEquals(versionAfterCreate, eltm.writeVersion, "a fetch touches nothing")

        // an ended triple is ALSO fetched as-is: validity never changes here
        val relId = eltm.relationships.values.single().id
        provider.execute(
            toolCall("c3", "add_relationship_note", buildJsonObject {
                put("relationship_id", relId)
                put("event_date", "2026-08-19")
                put("note", "the collaboration ended")
                put("valid", false)
            }),
        )
        val versionAfterEnd = eltm.writeVersion
        val refetched = provider.execute(
            toolCall("c4", "create_relationship", buildJsonObject {
                put("src_id", alice.id)
                put("dst_id", bob.id)
                put("verb", "works at")
            }),
        )
        assertFalse(refetched.isError, "an ended triple is fetched as-is: ${textOf(refetched)}")
        assertTrue(
            textOf(refetched).contains("invalidated"),
            "the fetched state is visible: ${textOf(refetched)}"
        )
        assertFalse(eltm.relationships.values.single().valid, "the fetch never flips validity")
        assertEquals(versionAfterEnd, eltm.writeVersion, "the fetch touches nothing")
    }

    @Test
    fun `create_relationship fails fast on a missing endpoint`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "create_relationship", buildJsonObject {
                put("src_id", alice.id)
                put("dst_id", 999)
                put("verb", "works_at")
            }),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("999"), textOf(result))
    }

    @Test
    fun `add_relationship_note's valid flag closes and revives the relationship idempotently`() =
        runBlocking {
            val eltm = FakeEltmService()
            val alice = eltm.createEntity("Alice", "person").entity
            val bob = eltm.createEntity("Bob", "person").entity
            val rel = eltm.createRelationship(alice.id, bob.id, "works_at")
            val provider = EltmToolProvider(eltm)

            val ok = provider.execute(
                toolCall("c1", "add_relationship_note", buildJsonObject {
                    put("relationship_id", rel.id)
                    put("event_date", "2026-08-19")
                    put("note", "left the company")
                    put("valid", false)
                }),
            )
            assertFalse(ok.isError, textOf(ok))
            assertFalse(eltm.relationships[rel.id]!!.valid, "the ending note closes the relationship")
            assertEquals(1, eltm.notes.size, "the ending note is recorded")

            // an already-closed relationship still accepts the note (the diary
            // is content truth): the close is idempotent, NOT an error
            val again = provider.execute(
                toolCall("c2", "add_relationship_note", buildJsonObject {
                    put("relationship_id", rel.id)
                    put("event_date", "2026-08-20")
                    put("note", "it stays over")
                    put("valid", false)
                }),
            )
            assertFalse(again.isError, "a note on an already-closed relationship still attaches: ${textOf(again)}")
            assertEquals(2, eltm.notes.size)
            assertFalse(eltm.relationships[rel.id]!!.valid)

            // a revival event re-opens the SAME row
            val revived = provider.execute(
                toolCall("c3", "add_relationship_note", buildJsonObject {
                    put("relationship_id", rel.id)
                    put("event_date", "2026-08-21")
                    put("note", "rejoined the company")
                    put("valid", true)
                }),
            )
            assertFalse(revived.isError, textOf(revived))
            assertTrue(eltm.relationships[rel.id]!!.valid, "the revival event re-opens the edge")
            assertEquals(3, eltm.notes.size)

            // setting the current state again is a no-op, not an error
            val noop = provider.execute(
                toolCall("c4", "add_relationship_note", buildJsonObject {
                    put("relationship_id", rel.id)
                    put("event_date", "2026-08-22")
                    put("note", "still there")
                    put("valid", true)
                }),
            )
            assertFalse(noop.isError, textOf(noop))
            assertTrue(eltm.relationships[rel.id]!!.valid)

            // a missing relationship fails before the embed
            val missing = provider.execute(
                toolCall("c5", "add_relationship_note", buildJsonObject {
                    put("relationship_id", 999)
                    put("event_date", "2026-08-20")
                    put("note", "ghost")
                    put("valid", true)
                }),
            )
            assertTrue(missing.isError)
            assertTrue(textOf(missing).contains("999"), textOf(missing))
        }

    @Test
    fun `add_relationship_note renders the current state label`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val bob = eltm.createEntity("Bob", "person").entity
        val rel = eltm.createRelationship(alice.id, bob.id, "works_at")
        val provider = EltmToolProvider(eltm)

        val opened = provider.execute(
            toolCall("c1", "add_relationship_note", buildJsonObject {
                put("relationship_id", rel.id)
                put("event_date", "2026-08-19")
                put("note", "started working together")
            }),
        )
        assertFalse(opened.isError, textOf(opened))
        assertTrue(textOf(opened).contains("currently active"), textOf(opened))

        val closed = provider.execute(
            toolCall("c2", "add_relationship_note", buildJsonObject {
                put("relationship_id", rel.id)
                put("event_date", "2026-08-20")
                put("note", "left the company")
                put("valid", false)
            }),
        )
        assertFalse(closed.isError, textOf(closed))
        assertTrue(textOf(closed).contains("currently invalidated"), textOf(closed))

        val reopened = provider.execute(
            toolCall("c3", "add_relationship_note", buildJsonObject {
                put("relationship_id", rel.id)
                put("event_date", "2026-08-21")
                put("note", "rejoined the company")
                put("valid", true)
            }),
        )
        assertFalse(reopened.isError, textOf(reopened))
        assertTrue(textOf(reopened).contains("currently active"), textOf(reopened))
    }

    @Test
    fun `add_entity_note appends add-only and rejects a stray valid arg`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        val provider = EltmToolProvider(eltm)

        val badDate = provider.execute(
            toolCall("c1", "add_entity_note", buildJsonObject {
                put("entity_id", alice.id)
                put("event_date", "yesterday")
                put("note", "went to Paris")
            }),
        )
        assertTrue(badDate.isError, "a malformed date fails instead of being silently accepted")
        assertTrue(textOf(badDate).contains("YYYY-MM-DD"), textOf(badDate))

        val added = provider.execute(
            toolCall("c2", "add_entity_note", buildJsonObject {
                put("entity_id", alice.id)
                put("event_date", "2026-08-18")
                put("note", "went to Paris")
            }),
        )
        assertFalse(added.isError)
        val note = eltm.notes.values.single()
        assertTrue(note.entityId == alice.id && note.relationshipId == null)
        assertEquals(LocalDate.parse("2026-08-18"), note.eventDate)

        // the split tool has no valid flag: a stray arg is an error, not a
        // silently ignored structural change
        val strayValid = provider.execute(
            toolCall("c3", "add_entity_note", buildJsonObject {
                put("entity_id", alice.id)
                put("event_date", "2026-08-18")
                put("note", "still here")
                put("valid", false)
            }),
        )
        assertTrue(strayValid.isError)
        assertTrue(textOf(strayValid).contains("valid only applies"), textOf(strayValid))
        assertEquals(1, eltm.notes.size, "the rejected call appends nothing")

        val missing = provider.execute(
            toolCall("c4", "add_entity_note", buildJsonObject {
                put("entity_id", 999)
                put("event_date", "2026-08-18")
                put("note", "ghost")
            }),
        )
        assertTrue(missing.isError)
        assertTrue(textOf(missing).contains("999"), textOf(missing))
    }

    @Test
    fun `merge_entities folds a re-pointed triple into the one existing row and folds the validity`() =
        runBlocking {
            val eltm = FakeEltmService()
            val acme = eltm.createEntity("Acme", "company").entity
            val acmeInc = eltm.createEntity("Acme Inc", "company").entity
            val bob = eltm.createEntity("Bob", "person").entity
            val provider = EltmToolProvider(eltm)

            // one row per triple: the canonical entity's edge is CLOSED...
            val winnerRel = eltm.createRelationship(acme.id, bob.id, "works_at")
            eltm.attachNoteToRelationship(
                winnerRel.id, LocalDate.parse("2026-08-01"), "left the company", valid = false,
            )
            // ...the duplicate's edge is ACTIVE, with its own diary note
            val loserRel = eltm.createRelationship(acmeInc.id, bob.id, "works_at")
            eltm.attachNoteToRelationship(
                loserRel.id, LocalDate.parse("2026-08-05"), "joined the company",
            )

            val result = provider.execute(
                toolCall("c1", "merge_entities", buildJsonObject {
                    put("winner_id", acme.id)
                    put("loser_id", acmeInc.id)
                }),
            )
            assertFalse(result.isError, textOf(result))
            val remaining = eltm.relationships.values.single()
            assertTrue(
                remaining.valid,
                "the survivor holds the edge because either row held it"
            )
            assertEquals(2, eltm.notes.size, "both diary notes survive the fold")
            assertTrue(
                eltm.notes.values.all { it.relationshipId == remaining.id },
                "the duplicate's notes re-point to the survivor, never cascade-deleted"
            )
        }

    @Test
    fun `merge_entities folds the loser into the winner`() = runBlocking {
        val eltm = FakeEltmService()
        val winner = eltm.createEntity("Apple", "company").entity
        val loser = eltm.createEntity("Apple Inc", "company").entity
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "merge_entities", buildJsonObject {
                put("winner_id", winner.id)
                put("loser_id", loser.id)
            }),
        )
        assertFalse(result.isError)
        assertTrue(textOf(result).contains("merged"), textOf(result))
        assertEquals(listOf(winner.id to loser.id), eltm.merged)
        assertNull(eltm.entities[loser.id], "the loser row is gone")
        assertNotNull(eltm.entities[winner.id])
    }

    @Test
    fun `set_entity_attribute sets, no-ops and overwrites`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        val provider = EltmToolProvider(eltm)

        val set = provider.execute(
            toolCall("c1", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
                put("value", "Paperwhite 6")
            }),
        )
        assertFalse(set.isError, textOf(set))
        assertTrue(textOf(set).contains("Attribute \"model\" set on entity"), textOf(set))
        assertEquals<Map<String, String>?>(mapOf("model" to "Paperwhite 6"), eltm.attributes[kindle.id])
        val versionAfterSet = eltm.writeVersion

        // the same value again is a no-op: nothing bumps
        val noop = provider.execute(
            toolCall("c2", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
                put("value", "Paperwhite 6")
            }),
        )
        assertFalse(noop.isError, textOf(noop))
        assertTrue(textOf(noop).contains("already set"), textOf(noop))
        assertEquals(versionAfterSet, eltm.writeVersion, "a no-op set touches nothing")

        // a different value overwrites and bumps
        val overwrite = provider.execute(
            toolCall("c3", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
                put("value", "Paperwhite 7")
            }),
        )
        assertFalse(overwrite.isError, textOf(overwrite))
        assertEquals(versionAfterSet + 1, eltm.writeVersion)
        assertEquals<Map<String, String>?>(mapOf("model" to "Paperwhite 7"), eltm.attributes[kindle.id])

        // a missing entity fails fast
        val missing = provider.execute(
            toolCall("c4", "set_entity_attribute", buildJsonObject {
                put("entity_id", 999)
                put("key", "model")
                put("value", "x")
            }),
        )
        assertTrue(missing.isError)
        assertTrue(textOf(missing).contains("999"), textOf(missing))
    }

    @Test
    fun `set_entity_attribute canonicalizes the key and rejects multi-line values`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        val provider = EltmToolProvider(eltm)

        val set = provider.execute(
            toolCall("c1", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "  Real Name  ")
                put("value", "Alice")
            }),
        )
        assertFalse(set.isError, textOf(set))
        assertTrue(textOf(set).contains("Attribute \"real_name\" set on entity"), textOf(set))
        assertEquals<Map<String, String>?>(mapOf("real_name" to "Alice"), eltm.attributes[kindle.id])

        val multiLine = provider.execute(
            toolCall("c2", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "note")
                put("value", "line one\nline two")
            }),
        )
        assertTrue(multiLine.isError)
        assertTrue(textOf(multiLine).contains("single line"), textOf(multiLine))
        assertEquals<Map<String, String>?>(mapOf("real_name" to "Alice"), eltm.attributes[kindle.id], "nothing mutates")
    }

    @Test
    fun `delete_entity_attribute removes the fact and fails fast on a missing key`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        eltm.setEntityAttribute(kindle.id, "model", "Paperwhite 6")
        eltm.setEntityAttribute(kindle.id, "nickname", "reader")
        val versionAfterSet = eltm.writeVersion
        val provider = EltmToolProvider(eltm)

        val removed = provider.execute(
            toolCall("c1", "delete_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
            }),
        )
        assertFalse(removed.isError, textOf(removed))
        assertTrue(textOf(removed).contains("Attribute \"model\" removed"), textOf(removed))
        assertEquals(versionAfterSet + 1, eltm.writeVersion, "a delete bumps")
        assertEquals<Map<String, String>?>(mapOf("nickname" to "reader"), eltm.attributes[kindle.id])

        val missingKey = provider.execute(
            toolCall("c2", "delete_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
            }),
        )
        assertTrue(missingKey.isError)
        assertTrue(textOf(missingKey).contains("does not exist"), textOf(missingKey))

        val missingEntity = provider.execute(
            toolCall("c3", "delete_entity_attribute", buildJsonObject {
                put("entity_id", 999)
                put("key", "model")
            }),
        )
        assertTrue(missingEntity.isError)
        assertTrue(textOf(missingEntity).contains("999"), textOf(missingEntity))
    }

    @Test
    fun `search_entities renders the entity attributes alphabetically`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        eltm.setEntityAttribute(kindle.id, "realname", "Alice")
        eltm.setEntityAttribute(kindle.id, "model", "Paperwhite 6")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "search_entities", buildJsonObject { put("query", "kindle") }),
        )
        assertFalse(result.isError, textOf(result))
        val text = textOf(result)
        assertTrue(
            text.contains("Attributes:\n  model: Paperwhite 6\n  realname: Alice"),
            "attributes render alphabetically, one indented line per key: $text",
        )
    }

    @Test
    fun `merge_entities folds the loser's attributes into the winner`() = runBlocking {
        val eltm = FakeEltmService()
        val winner = eltm.createEntity("Apple", "company").entity
        val loser = eltm.createEntity("Apple Inc", "company").entity
        eltm.setEntityAttribute(winner.id, "ticker", "AAPL")
        eltm.setEntityAttribute(winner.id, "hq", "Cupertino")
        eltm.setEntityAttribute(loser.id, "ticker", "APPL")
        eltm.setEntityAttribute(loser.id, "founded", "1976")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "merge_entities", buildJsonObject {
                put("winner_id", winner.id)
                put("loser_id", loser.id)
            }),
        )
        assertFalse(result.isError, textOf(result))
        assertEquals<Map<String, String>?>(
            mapOf("ticker" to "AAPL", "hq" to "Cupertino", "founded" to "1976"),
            eltm.attributes[winner.id],
            "the winner keeps its value on a colliding key, the loser's unique key folds in",
        )
        assertNull(eltm.attributes[loser.id], "the loser's attribute rows are gone with the merge")
    }

    @Test
    fun `a merge whose fold re-embed fails rolls back without mutating`() = runBlocking {
        val eltm = FakeEltmService()
        val winner = eltm.createEntity("Apple", "company").entity
        val loser = eltm.createEntity("Apple Inc", "company").entity
        eltm.setEntityAttribute(loser.id, "founded", "1976")
        val versionBefore = eltm.writeVersion
        eltm.embedFailure = EmbeddingException(
            "invalid_request", "input too large for the embedding model"
        )
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "merge_entities", buildJsonObject {
                put("winner_id", winner.id)
                put("loser_id", loser.id)
            }),
        )
        assertTrue(result.isError)
        val text = textOf(result)
        assertTrue(text.contains("shorten or delete an attribute"), text)
        assertFalse(
            text.contains("split it into several smaller notes"),
            "an attribute cannot be split, the merge must be fixed by its attributes: $text"
        )
        assertEquals(versionBefore, eltm.writeVersion, "a failed merge bumps nothing")
        assertNotNull(eltm.entities[loser.id], "the loser row survives a failed merge")
        assertEquals<Map<String, String>?>(
            mapOf("founded" to "1976"),
            eltm.attributes[loser.id],
            "the loser's attributes survive a failed merge",
        )
    }

    @Test
    fun `attribute writes fail on an embedding failure without mutating`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        eltm.embedFailure = EmbeddingException("upstream", "gateway timeout")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
                put("value", "Paperwhite 6")
            }),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("embedding failed"), textOf(result))
        assertNull(eltm.attributes[kindle.id], "a failed embed leaves the store untouched")
    }

    @Test
    fun `delete_entity_attribute fails on an embedding failure without mutating`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        eltm.setEntityAttribute(kindle.id, "model", "Paperwhite 6")
        eltm.embedFailure = EmbeddingException("upstream", "gateway timeout")
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "delete_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "model")
            }),
        )
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("embedding failed"), textOf(result))
        assertEquals<Map<String, String>?>(
            mapOf("model" to "Paperwhite 6"),
            eltm.attributes[kindle.id],
            "a failed embed leaves the store untouched",
        )
    }

    @Test
    fun `an embedding too-large error tells the model to split the content`() = runBlocking {
        val eltm = FakeEltmService()
        val alice = eltm.createEntity("Alice", "person").entity
        eltm.embedFailure = EmbeddingException(
            "invalid_request", "input too large for the embedding model"
        )
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "add_entity_note", buildJsonObject {
                put("entity_id", alice.id)
                put("event_date", "2026-08-18")
                put("note", "a very long note")
            }),
        )
        assertTrue(result.isError)
        val text = textOf(result)
        assertTrue(text.contains("split it into several smaller notes"), text)
        assertFalse(text.contains("embedding failed"), "the too-large case has its own message")

        // other embedding failures are generic errors the loop may retry
        eltm.embedFailure = EmbeddingException("upstream", "gateway timeout")
        val retryable = provider.execute(
            toolCall("c2", "add_entity_note", buildJsonObject {
                put("entity_id", alice.id)
                put("event_date", "2026-08-18")
                put("note", "another note")
            }),
        )
        assertTrue(retryable.isError)
        assertTrue(textOf(retryable).contains("embedding failed"), textOf(retryable))
    }

    @Test
    fun `an embedding too-large error on an attribute tells the model to shorten the value`() = runBlocking {
        val eltm = FakeEltmService()
        val kindle = eltm.createEntity("kindle", "device").entity
        eltm.embedFailure = EmbeddingException(
            "invalid_request", "input too large for the embedding model"
        )
        val provider = EltmToolProvider(eltm)

        val result = provider.execute(
            toolCall("c1", "set_entity_attribute", buildJsonObject {
                put("entity_id", kindle.id)
                put("key", "serial")
                put("value", "a very long value")
            }),
        )
        assertTrue(result.isError)
        val text = textOf(result)
        assertTrue(text.contains("shorten the value"), text)
        assertFalse(
            text.contains("split it into several smaller notes"),
            "an attribute is a single fact, it cannot be split: $text"
        )
        assertNull(eltm.attributes[kindle.id], "a failed embed leaves the store untouched")
    }

    @Test
    fun `writer input lists the extracted facts verbatim with a current date header`() {
        val writer = testEltmWriterService(FakeHand())
        val input = EltmWriterService.buildWriterInput(
            facts = "likes coffee",
            date = LocalDate.parse("2026-08-18"),
        )
        assertTrue(input.startsWith("Current date: 2026-08-18\n\n"), input)
        assertTrue(input.contains("Candidate facts extracted from a discarded conversation"), input)
        assertTrue(input.contains("likes coffee"), "facts verbatim: $input")
    }

    @Test
    fun `a namespaced provider advertises the prefixed tool names and its namespace`() {
        val provider = EltmToolProvider(FakeEltmService(), namespace = "eltm")
        assertEquals(setOf("eltm"), provider.namespaces())
        val specs = runBlocking { provider.specifications() }
        assertEquals(13, specs.size)
        assertTrue(
            specs.all { it.name.startsWith("eltm__") },
            "every advertised name carries the namespace prefix: ${specs.map { it.name }}"
        )
        assertEquals(
            listOf("eltm__search_entities", "eltm__get_relationships"),
            specs.take(2).map { it.name },
        )
        // the blank provider is the one-shot shape: bare names, no namespace
        assertEquals(emptySet(), EltmToolProvider(FakeEltmService()).namespaces())
    }

    @Test
    fun `a namespaced provider executes its prefixed calls`() = runBlocking {
        val eltm = FakeEltmService()
        val provider = EltmToolProvider(eltm, namespace = "eltm")

        val created = provider.execute(
            toolCall("c1", "eltm__create_entity", buildJsonObject { put("name", "Alice") }),
        )
        assertFalse(created.isError, textOf(created))
        assertTrue(textOf(created).contains("alice"), textOf(created))

        val searched = provider.execute(
            toolCall("c2", "eltm__search_entities", buildJsonObject { put("query", "ali") }),
        )
        assertFalse(searched.isError, textOf(searched))
        assertTrue(textOf(searched).contains("alice"), textOf(searched))
    }

    @Test
    fun `a namespaced provider rejects unprefixed and wrong-prefixed calls`() = runBlocking {
        val provider = EltmToolProvider(FakeEltmService(), namespace = "eltm")

        val bare = provider.execute(
            toolCall("c1", "search_entities", buildJsonObject { put("query", "ali") }),
        )
        assertTrue(bare.isError)
        assertTrue(textOf(bare).contains("not advertised"), textOf(bare))

        val foreign = provider.execute(
            toolCall("c2", "fs__search_entities", buildJsonObject { put("query", "ali") }),
        )
        assertTrue(foreign.isError)
        assertTrue(textOf(foreign).contains("not advertised"), textOf(foreign))
    }

    @Test
    fun `a namespaced read-only provider checks the stripped name`() = runBlocking {
        val provider = EltmToolProvider(FakeEltmService(), readOnly = true, namespace = "eltm")

        val write = provider.execute(
            toolCall("c1", "eltm__create_entity", buildJsonObject { put("name", "Alice") }),
        )
        assertTrue(write.isError)
        assertTrue(textOf(write).contains("read-only mode"), textOf(write))

        val read = provider.execute(
            toolCall("c2", "eltm__search_entities", buildJsonObject { put("query", "ali") }),
        )
        assertFalse(read.isError, textOf(read))
    }

    @Test
    fun `an invalid namespace fails at construction`() {
        // uppercase fails the SAFE_ID_REGEX charset; `__` is the separator;
        // a leading/trailing `_` would blur the separator boundary
        assertFailsWith<IllegalArgumentException> {
            EltmToolProvider(FakeEltmService(), namespace = "ElTm")
        }
        assertFailsWith<IllegalArgumentException> {
            EltmToolProvider(FakeEltmService(), namespace = "a__b")
        }
        assertFailsWith<IllegalArgumentException> {
            EltmToolProvider(FakeEltmService(), namespace = "a_")
        }
        assertFailsWith<IllegalArgumentException> {
            EltmToolProvider(FakeEltmService(), namespace = "_a")
        }
        // blank stays legal: the one-shot shape
        EltmToolProvider(FakeEltmService(), namespace = "")
    }
}
