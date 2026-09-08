package n.startapp.services.context

import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.services.ai.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Подбор значения по русскому переводу — там, где быстрая подсказка решает, что человек сохранит.
 *
 * Здесь проверяется не «нашлось ли», а «имел ли право найтись». Один русский глагол покрывает
 * несколько значений сразу, и первое из двух одинаково подходящих выглядит ответом, читается как
 * ответ и кладёт слово в словарь под смыслом, которого никто не выбирал.
 */
class ContextHintMatchTest {

    private val service = ContextAnalysisService(
        llm = object : LlmClient {
            override suspend fun complete(request: n.startapp.services.ai.LlmRequest) =
                throw UnsupportedOperationException("не нужен: проверяется подбор, а не модель")

            override fun close() = Unit
        },
        entryRepository = LexicalEntryRepository()
    )

    private fun match(entry: LexicalEntry, translation: String?, pos: String?): Sense? {
        val method = ContextAnalysisService::class.java
            .getDeclaredMethod(
                "matchSenseByTranslation",
                LexicalEntry::class.java,
                String::class.java,
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(service, entry, translation, pos) as Sense?
    }

    private fun sense(id: String, definitionEn: String, vararg ru: String) = Sense(
        id = id,
        definitionEn = definitionEn,
        definitionRu = "",
        translationsRu = ru.toList()
    )

    private fun entry(vararg groups: PosGroup) = LexicalEntry(
        lemma = "lead",
        posGroups = groups.toList()
    )

    @Test
    fun `a translation that fits one sense and no other matches it`() {
        val article = entry(
            PosGroup(
                pos = "noun",
                posRu = "существительное",
                senses = listOf(
                    sense("n1", "the heavy grey metal", "свинец"),
                    sense("n2", "a strap for a dog", "поводок")
                )
            )
        )

        assertEquals("n1", match(article, "свинец", "noun")?.id)
        assertEquals("n2", match(article, "поводок", "noun")?.id)
    }

    @Test
    fun `a translation shared by two senses matches neither`() {
        // «вести» — это и возглавлять организацию, и вести лошадь под уздцы. Верхняя оценка
        // ничего не говорит о том, какое из двух имелось в виду.
        val article = entry(
            PosGroup(
                pos = "verb",
                posRu = "глагол",
                senses = listOf(
                    sense("v1", "to be in charge of an organization", "вести", "возглавлять"),
                    sense("v2", "to guide an animal or person by hand", "вести", "направлять")
                )
            )
        )

        assertNull(match(article, "вести", "verb"))
    }

    @Test
    fun `the ending is not what decides it`() {
        // Подсказка переводит слово как оно стоит — «свинцовая», — а статья хранит словарную
        // форму. Буквальное сравнение не совпадало бы почти никогда.
        val article = entry(
            PosGroup(
                pos = "adjective",
                posRu = "прилагательное",
                senses = listOf(sense("a1", "made of lead", "свинцовый"))
            )
        )

        assertEquals("a1", match(article, "свинцовая", "adjective")?.id)
    }

    @Test
    fun `no translation and no overlap are both misses, not guesses`() {
        val article = entry(
            PosGroup(pos = "noun", posRu = "существительное", senses = listOf(sense("n1", "the heavy grey metal", "свинец")))
        )

        assertNull(match(article, null, "noun"))
        assertNull(match(article, "поводок", "noun"))
    }

    @Test
    fun `a whole word beats a stem, so a plural does not land on a lookalike sense`() {
        // Живой случай из книги: «After the horses came Muriel» → «лошадей». Обрубки «лошадей»
        // и «лошадка» совпадали, и подсказка приезжала со значением «„лошадка“ —
        // баскетбольная игра»: с пометами, с определением и с видом полной уверенности.
        val article = entry(
            PosGroup(
                pos = "noun",
                posRu = "существительное",
                senses = listOf(
                    sense("n1", "a large four-legged mammal", "лошадь", "конь"),
                    sense("n5", "an informal basketball game", "«лошадка» (баскетбольная игра)")
                )
            )
        )

        // Словарная форма по-прежнему находит своё значение целым словом.
        assertEquals("n1", match(article, "лошадь", "noun")?.id)
        // А форма, у которой целого совпадения нет, упирается в ничью обрубков — и это промах,
        // а не выбор наугад между «лошадью» и баскетболом.
        assertNull(match(article, "лошадей", "noun"))
    }

    @Test
    fun `a stem still matches when nothing competes with it`() {
        val article = entry(
            PosGroup(
                pos = "noun",
                posRu = "существительное",
                senses = listOf(
                    sense("n1", "a large four-legged mammal", "лошадь", "конь"),
                    sense("n2", "a strap for a dog", "поводок")
                )
            )
        )

        assertEquals("n1", match(article, "лошадей", "noun")?.id)
    }

    @Test
    fun `a part of speech nobody wrote about does not narrow the article to nothing`() {
        // Модель может назвать часть речи, которой в статье нет: искать тогда надо по всей
        // статье, а не отвечать «не нашлось» из-за пометы.
        val article = entry(
            PosGroup(pos = "noun", posRu = "существительное", senses = listOf(sense("n1", "the heavy grey metal", "свинец")))
        )

        assertEquals("n1", match(article, "свинец", "adjective")?.id)
    }
}
