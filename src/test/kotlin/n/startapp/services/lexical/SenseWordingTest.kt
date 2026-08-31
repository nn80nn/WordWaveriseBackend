package n.startapp.services.lexical

import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.lexical.BilingualExample
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The single place a sense becomes the two lines a saved word and a card show — plus, now, how
 * the word sounds on that card.
 */
class SenseWordingTest {

    /** A homograph: same spelling, two parts of speech, two pronunciations. */
    private fun lead() = LexicalEntry(
        lemma = "lead",
        phonetic = "/liːd/",
        audioUrl = "https://audio/entry.mp3",
        posGroups = listOf(
            PosGroup(
                pos = "verb",
                posRu = "глагол",
                pronunciations = listOf(
                    PronunciationEntry(region = "uk", ipa = "/liːd/", audioMp3Url = "https://audio/verb-uk.mp3")
                ),
                senses = listOf(
                    Sense(
                        id = "v1",
                        definitionEn = "to guide a group of people",
                        definitionRu = "вести за собой",
                        translationsRu = listOf("вести"),
                        examples = listOf(BilingualExample(en = "She led the team.", ru = "Она вела команду."))
                    )
                )
            ),
            PosGroup(
                pos = "noun",
                posRu = "существительное",
                pronunciations = listOf(
                    PronunciationEntry(region = "uk", ipa = "/led/", audioMp3Url = "https://audio/noun-uk.mp3")
                ),
                senses = listOf(
                    Sense(
                        id = "n1",
                        definitionEn = "a soft heavy metal",
                        definitionRu = "мягкий тяжёлый металл",
                        translationsRu = listOf("свинец")
                    )
                )
            )
        )
    )

    @Test
    fun `a card takes the pronunciation of its own part of speech`() {
        // Это и есть причина, по которой произношение хранится по частям речи: карточка про
        // «свинец» не должна проигрывать запись глагола.
        val noun = SenseWording.of(lead(), "n1")
        assertEquals("/led/", noun?.phonetic)
        assertEquals("https://audio/noun-uk.mp3", noun?.audioUrl)

        val verb = SenseWording.of(lead(), "v1")
        assertEquals("/liːd/", verb?.phonetic)
        assertEquals("https://audio/verb-uk.mp3", verb?.audioUrl)
    }

    @Test
    fun `an entry with no per-part-of-speech sound falls back to the article's own`() {
        val plain = LexicalEntry(
            lemma = "omit",
            phonetic = "/əˈmɪt/",
            audioUrl = "https://audio/omit.mp3",
            posGroups = listOf(
                PosGroup(
                    pos = "verb",
                    posRu = "глагол",
                    senses = listOf(
                        Sense(id = "v1", definitionEn = "to leave out", definitionRu = "пропустить")
                    )
                )
            )
        )

        val wording = SenseWording.of(plain, "v1")
        assertEquals("/əˈmɪt/", wording?.phonetic)
        assertEquals("https://audio/omit.mp3", wording?.audioUrl)
    }

    @Test
    fun `the card's definition and example come from the pinned sense`() {
        val verb = SenseWording.of(lead(), "v1")

        assertEquals("to guide a group of people", verb?.definition)
        assertEquals("She led the team.", verb?.example)
        assertEquals("вести", verb?.translation)
    }

    @Test
    fun `an unknown sense yields nothing at all`() {
        assertNull(SenseWording.of(lead(), "v9"))
    }

    @Test
    fun `a sense with its own pronunciation overrules its part of speech`() {
        // Два существительных под одним написанием: /liːd/ «лидерство» и /led/ «свинец».
        // Часть речи их не различает — различает только значение, поэтому оно и побеждает.
        val entry = LexicalEntry(
            lemma = "lead",
            phonetic = "/liːd/",
            audioUrl = "https://audio/entry.mp3",
            posGroups = listOf(
                PosGroup(
                    pos = "noun",
                    posRu = "существительное",
                    pronunciations = listOf(
                        PronunciationEntry(region = "uk", ipa = "/liːd/", audioMp3Url = "https://audio/noun-uk.mp3")
                    ),
                    senses = listOf(
                        Sense(
                            id = "n1",
                            definitionEn = "first place in a race",
                            definitionRu = "лидерство",
                            translationsRu = listOf("лидерство")
                        ),
                        Sense(
                            id = "n2",
                            definitionEn = "a soft heavy metal",
                            definitionRu = "мягкий тяжёлый металл",
                            translationsRu = listOf("свинец"),
                            phonetic = "/led/",
                            audioUrl = "https://audio/metal.mp3"
                        )
                    )
                )
            )
        )

        assertEquals("/liːd/", SenseWording.of(entry, "n1")?.phonetic)
        assertEquals("/led/", SenseWording.of(entry, "n2")?.phonetic)
        assertEquals("https://audio/metal.mp3", SenseWording.of(entry, "n2")?.audioUrl)
    }

    @Test
    fun `a sense that sounds different and has no recording stays silent`() {
        // Запись части речи здесь была бы записью другого слова: лучше молча, чем вслух неверно.
        val entry = LexicalEntry(
            lemma = "bass",
            phonetic = "/beɪs/",
            audioUrl = "https://audio/entry.mp3",
            posGroups = listOf(
                PosGroup(
                    pos = "noun",
                    posRu = "существительное",
                    pronunciations = listOf(
                        PronunciationEntry(region = "uk", ipa = "/beɪs/", audioMp3Url = "https://audio/music.mp3")
                    ),
                    senses = listOf(
                        Sense(
                            id = "n1",
                            definitionEn = "a fish",
                            definitionRu = "окунь",
                            translationsRu = listOf("окунь"),
                            phonetic = "/bæs/"
                        )
                    )
                )
            )
        )

        assertEquals("/bæs/", SenseWording.of(entry, "n1")?.phonetic)
        assertNull(SenseWording.of(entry, "n1")?.audioUrl)
    }
}
