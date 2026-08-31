package n.startapp.services.lexical

import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenseBackfillTest {

    private fun sense(id: String) = Sense(id = id, definitionEn = id, definitionRu = id)

    private val entry = LexicalEntry(
        lemma = "resolve",
        posGroups = listOf(
            PosGroup("verb", "глагол", senses = listOf(sense("v1"), sense("v2"))),
            PosGroup("noun", "существительное", senses = listOf(sense("n1")))
        )
    )

    @Test
    fun `an unpinned word takes the sense it was already showing`() {
        // Строка без значения всегда показывала первое значение первой части речи — то же самое,
        // что вернёт SenseWording.of(entry, null). Привязка ничего не меняет на экране, она лишь
        // делает выбор явным и потому защищённым от перезаписи.
        assertEquals("v1", SenseBackfill.choose(entry))
    }

    @Test
    fun `a sense the user already holds is not handed out twice`() {
        // Уникальность пары «слово + значение» держится кодом, а не индексом: столкновение здесь
        // не упало бы с ошибкой, а тихо положило бы один и тот же смысл в словарь дважды.
        assertEquals("v2", SenseBackfill.choose(entry, taken = setOf("v1")))
        assertEquals("n1", SenseBackfill.choose(entry, taken = setOf("v1", "v2")))
        assertNull(SenseBackfill.choose(entry, taken = setOf("v1", "v2", "n1")))
    }

    @Test
    fun `a word whose article is not written yet keeps its null`() {
        // Придумать значение не из чего. Строка остаётся как есть и получит привязку на первом
        // же чтении после того, как статья появится.
        assertNull(SenseBackfill.choose(null))
        assertNull(SenseBackfill.choose(LexicalEntry(lemma = "grow up")))
    }
}
