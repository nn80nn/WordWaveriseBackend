package n.startapp.services.seo

import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.lexical.BilingualExample
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.models.lexical.SourceRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordPageRendererTest {

    private fun entry(
        lemma: String = "resolve",
        senses: List<Sense> = listOf(sense())
    ) = LexicalEntry(
        lemma = lemma,
        pronunciations = listOf(PronunciationEntry(region = "uk", ipa = "/rɪˈzɒlv/")),
        posGroups = listOf(PosGroup(pos = "verb", posRu = "глагол", senses = senses)),
        sources = listOf(SourceRef(index = 1, source = "Cambridge", definition = "to solve"))
    )

    private fun sense(
        definitionRu: String = "решить проблему или спор",
        translations: List<String> = listOf("решать", "разрешать")
    ) = Sense(
        id = "v1",
        definitionEn = "to solve or end a problem",
        definitionRu = definitionRu,
        translationsRu = translations,
        examples = listOf(BilingualExample(en = "They resolved the dispute.", ru = "Они разрешили спор.")),
        synonyms = listOf("settle", "sort out")
    )

    @Test
    fun `renders the article text into the markup`() {
        val html = WordPageRenderer.wordPage(entry())

        // The whole point of these pages: the content is in the HTML, not fetched by JS.
        assertContains(html, "<h1>resolve</h1>")
        assertContains(html, "решить проблему или спор")
        assertContains(html, "They resolved the dispute.")
        assertContains(html, "Они разрешили спор.")
        assertContains(html, "/rɪˈzɒlv/")
        assertFalse(html.contains("<div id=\"app\">"), "must not depend on the SPA shell")
    }

    @Test
    fun `head carries a canonical, a description and structured data`() {
        val html = WordPageRenderer.wordPage(entry())

        assertContains(html, "rel=\"canonical\"")
        assertContains(html, "/word/resolve\">")
        assertContains(html, "application/ld+json")
        assertContains(html, "DefinedTerm")
        assertContains(html, "<meta name=\"description\" content=\"resolve — решать, разрешать.")
    }

    @Test
    fun `the breadcrumb trail is also declared as structured data`() {
        val html = WordPageRenderer.wordPage(entry())

        assertContains(html, "BreadcrumbList")
        assertContains(html, "https://wordwaverise.com/words/r")
        assertContains(html, "https://wordwaverise.com/word/resolve")
        // Two separate ld+json blocks, not one merged object.
        assertEquals(2, Regex("application/ld").findAll(html).count())
    }

    @Test
    fun `synonyms become links so a crawler can walk the corpus`() {
        val html = WordPageRenderer.wordPage(entry())

        assertContains(html, "href=\"/word/settle\"")
        // A multi-word synonym still needs a usable URL.
        assertContains(html, "href=\"/word/sort%20out\"")
    }

    @Test
    fun `text from the model cannot inject markup`() {
        val hostile = entry(senses = listOf(sense(definitionRu = "<script>alert(1)</script> & \"quoted\"")))
        val html = WordPageRenderer.wordPage(hostile)

        assertFalse(html.contains("<script>alert(1)</script>"))
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quoted&quot;")
    }

    @Test
    fun `word paths are lowercase and url encoded`() {
        assertEquals("/word/run", WordPageRenderer.wordPath("Run"))
        assertEquals("/word/give%20up", WordPageRenderer.wordPath("give up"))
    }

    @Test
    fun `a missing word is not indexable`() {
        val html = WordPageRenderer.missingPage("zzzqq")

        assertContains(html, "noindex")
        assertFalse(html.contains("rel=\"canonical\""))
    }

    @Test
    fun `letter page lists every lemma once`() {
        val html = WordPageRenderer.letterPage("r", listOf("run", "resolve", "rest"))

        assertContains(html, "Слова на букву R")
        assertContains(html, "3 слова")
        assertTrue(listOf("run", "resolve", "rest").all { html.contains("href=\"/word/$it\"") })
    }

    @Test
    fun `alphabet page hides letters with no articles`() {
        val html = WordPageRenderer.alphabetPage(listOf("a" to 12, "b" to 0), 12)

        assertContains(html, "href=\"/words/a\"")
        assertFalse(html.contains("href=\"/words/b\""))
        assertContains(html, "12 статей")
    }
}
