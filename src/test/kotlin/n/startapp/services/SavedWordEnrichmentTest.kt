package n.startapp.services

import n.startapp.models.auth.SavedWord
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedWordEnrichmentTest {

    private fun saved(definition: String? = null, translation: String? = null) = SavedWord(
        id = 1,
        userId = 1,
        word = "omit",
        translation = translation,
        definition = definition,
        savedAt = Instant.EPOCH
    )

    private fun entry(
        definitionEn: String = "to fail to include something",
        definitionRu: String = "не включить, пропустить что-либо",
        translations: List<String> = listOf("пропускать", "опускать")
    ) = LexicalEntry(
        lemma = "omit",
        posGroups = listOf(
            PosGroup(
                pos = "verb",
                posRu = "глагол",
                senses = listOf(
                    Sense(
                        id = "v1",
                        definitionEn = definitionEn,
                        definitionRu = definitionRu,
                        translationsRu = translations
                    )
                )
            )
        )
    )

    @Test
    fun `a word saved without a definition gets one from the corpus`() {
        val filled = SavedWordEnrichment.fill(saved(), entry())

        assertEquals("to fail to include something", filled?.definition)
        assertEquals("пропускать, опускать", filled?.translation)
    }

    @Test
    fun `an existing definition is never overwritten`() {
        val mine = saved(definition = "my own note", translation = "моё")

        assertNull(SavedWordEnrichment.fill(mine, entry()))
    }

    @Test
    fun `only the blank half is filled`() {
        val filled = SavedWordEnrichment.fill(saved(definition = "my own note"), entry())

        assertEquals("my own note", filled?.definition)
        assertEquals("пропускать, опускать", filled?.translation)
    }

    @Test
    fun `a blank string counts as missing, not as content`() {
        val filled = SavedWordEnrichment.fill(saved(definition = "   ", translation = ""), entry())

        assertEquals("to fail to include something", filled?.definition)
        assertEquals("пропускать, опускать", filled?.translation)
    }

    @Test
    fun `the russian explanation stands in when there is no english one`() {
        val filled = SavedWordEnrichment.fill(saved(), entry(definitionEn = ""))

        assertEquals("не включить, пропустить что-либо", filled?.definition)
    }

    @Test
    fun `a word the corpus does not know is left alone`() {
        assertNull(SavedWordEnrichment.fill(saved(), null))
        assertNull(SavedWordEnrichment.fill(saved(), LexicalEntry(lemma = "omit")))
    }

    @Test
    fun `nothing is written when the corpus adds nothing new`() {
        val already = saved(definition = "to fail to include something", translation = "пропускать, опускать")

        assertNull(SavedWordEnrichment.fill(already, entry()))
    }
}
