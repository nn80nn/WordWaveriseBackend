package n.startapp.database

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import n.startapp.database.tables.LexicalEntries
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.Users
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.SavedWordRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The one rule this migration has: no saved word may break, and none may disappear.
 *
 * Everything it does is additive — a row that had no sense gets one, and nothing else about it
 * moves. The interesting cases are the ones where the obvious implementation would quietly lose
 * something: a user who already holds the first sense, a word with no article, a row somebody
 * has already pinned by hand.
 */
class SavedWordSenseMigrationTest {

    private val words = SavedWordRepository()
    private val entries = LexicalEntryRepository()
    private val json = Json { encodeDefaults = true }

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("sense_migration", block)

    private fun user(): Int = transaction {
        Users.insert {
            it[email] = "u${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    private fun row(owner: Int, word: String, senseId: String? = null, translation: String? = null): Int =
        transaction {
            SavedWords.insert {
                it[userId] = owner
                it[SavedWords.word] = word
                it[SavedWords.senseId] = senseId
                it[SavedWords.translation] = translation
            }[SavedWords.id]
        }

    /** An article with two senses, stored the way a real one is. */
    private fun article(lemma: String, vararg senseIds: String) = transaction {
        val entry = LexicalEntry(
            lemma = lemma,
            posGroups = listOf(
                PosGroup(
                    pos = "verb", posRu = "глагол",
                    senses = senseIds.map { id ->
                        Sense(id = id, definitionEn = "definition $id", definitionRu = "значение $id",
                              translationsRu = listOf("перевод $id"))
                    }
                )
            )
        )
        LexicalEntries.insert {
            it[cacheKey] = "$lemma|WORD|1|test"
            it[LexicalEntries.lemma] = lemma
            it[lang] = "en"
            it[kind] = "WORD"
            it[schemaVersion] = entry.schemaVersion
            it[promptVersion] = 1
            it[model] = "test"
            it[sourceFingerprint] = "x"
            it[entryJson] = json.encodeToString(entry)
            it[rawJson] = ""
            it[formsIndex] = entry.formsIndex()
            it[createdAt] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    private fun senseOf(id: Int): String? = transaction {
        SavedWords.selectAll().where { SavedWords.id eq id }.single()[SavedWords.senseId]
    }

    @Test
    fun `a word saved without a sense is pinned to the first one`() = onFreshDatabase {
        val me = user()
        article("resolve", "v1", "v2")
        val id = row(me, "resolve")

        runBlocking { SavedWordSenseMigration.run(words, entries) }

        // Первое значение — ровно то, что строка и показывала: привязка делает выбор явным,
        // а не меняет его.
        assertEquals("v1", senseOf(id))
    }

    @Test
    fun `a sense the user already holds is not handed out twice`() = onFreshDatabase {
        val me = user()
        article("resolve", "v1", "v2")
        val pinnedByHand = row(me, "resolve", senseId = "v1")
        val legacy = row(me, "resolve")

        runBlocking { SavedWordSenseMigration.run(words, entries) }

        assertEquals("v1", senseOf(pinnedByHand))
        assertEquals("v2", senseOf(legacy))
    }

    @Test
    fun `a word with no article keeps its row and its null`() = onFreshDatabase {
        val me = user()
        val id = row(me, "grow up", translation = "взрослеть")

        runBlocking { SavedWordSenseMigration.run(words, entries) }

        // Придумать значение не из чего — но и терять строку не за что.
        assertNull(senseOf(id))
        assertEquals(
            "взрослеть",
            transaction { SavedWords.selectAll().where { SavedWords.id eq id }.single()[SavedWords.translation] }
        )
    }

    @Test
    fun `an existing pin is never moved`() = onFreshDatabase {
        val me = user()
        article("resolve", "v1", "v2")
        val id = row(me, "resolve", senseId = "v2")

        runBlocking { SavedWordSenseMigration.run(words, entries) }

        assertEquals("v2", senseOf(id))
    }

    @Test
    fun `two users of one word are pinned independently`() = onFreshDatabase {
        val me = user()
        val you = user()
        article("resolve", "v1", "v2")
        val mine = row(me, "resolve")
        val yours = row(you, "resolve")

        runBlocking { SavedWordSenseMigration.run(words, entries) }

        // Занятость считается по владельцу: чужая закладка ничего не занимает в моём словаре.
        assertEquals("v1", senseOf(mine))
        assertEquals("v1", senseOf(yours))
    }

    @Test
    fun `running twice changes nothing the second time`() = onFreshDatabase {
        val me = user()
        article("resolve", "v1", "v2")
        val id = row(me, "resolve")

        runBlocking {
            SavedWordSenseMigration.run(words, entries)
            SavedWordSenseMigration.run(words, entries)
        }

        assertEquals("v1", senseOf(id))
        assertEquals(1, transaction { SavedWords.selectAll().where { SavedWords.userId eq me }.count() }.toInt())
    }

    @Test
    fun `nothing is lost when the article has fewer senses than the rows`() = onFreshDatabase {
        val me = user()
        article("resolve", "v1")
        val first = row(me, "resolve")
        val second = row(me, "resolve")

        runBlocking { SavedWordSenseMigration.run(words, entries) }

        assertEquals("v1", senseOf(first))
        // Второй достаётся null, а не чужое значение и не удаление: строка человека остаётся.
        assertNull(senseOf(second))
        assertNotNull(transaction { SavedWords.selectAll().where { SavedWords.id eq second }.singleOrNull() })
        Unit
    }

    @Test
    fun `a row is never pinned on behalf of another account`() = onFreshDatabase {
        val teacher = user()
        val student = user()
        article("resolve", "v1", "v2")
        val theirs = row(teacher, "resolve")

        // Список ученика показывает и слова учителя — писать в них с чужого экрана нельзя.
        val wrote = runBlocking {
            words.pinSense(theirs, student, "v1", null, null, null)
        }

        assertEquals(false, wrote)
        assertNull(senseOf(theirs))
    }
}
