package n.startapp.services.scraper

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OxfordScraperTest {

    // Minimal HTML mirroring real Oxford Learner's Dictionaries structure for "playground"
    private val playgroundHtml = """
        <html><body>
        <div class="entry" id="playground">
          <div class="top-g" id="playground_topg_1">
            <div class="webtop">
              <h1 class="headword">playground</h1>
              <span class="pos">noun</span>
            </div>
          </div>
          <div class="phons_br" geo="br">
            <div data-src-mp3="https://www.oxfordlearnersdictionaries.com/media/english/uk_pron/p/pla/playg/playground__gb_3.mp3"
                 class="sound audio_play_button pron-uk icon-audio">
            </div>
            <span class="phon">/ˈpleɪɡraʊnd/</span>
          </div>
          <div class="phons_n_am" wd="playground">
            <div data-src-mp3="https://www.oxfordlearnersdictionaries.com/media/english/us_pron/p/pla/playg/playground__us_1.mp3"
                 class="sound audio_play_button pron-us icon-audio">
            </div>
            <span class="phon">/ˈpleɪɡraʊnd/</span>
          </div>
          <ol class="sense_list">
            <li class="sense" id="playground_sng_1">
              <span class="def">an outdoor area where children can play, especially at a school or in a park</span>
              <ul class="examples">
                <span class="x">children playing in the school playground</span>
              </ul>
            </li>
            <li class="sense" id="playground_sng_2">
              <span class="def">a place where a particular type of people go to enjoy themselves</span>
            </li>
          </ol>
        </div>
        </body></html>
    """.trimIndent()

    private val noContentHtml = "<html><body><p>No results found</p></body></html>"

    private val scraper = OxfordScraper(HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    })

    @Test
    fun `parseHtml extracts definitions from playground HTML`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertEquals("OXFORD", result.source)
        assertEquals("playground", result.word)
        assertEquals(2, result.senses.size)
        assertTrue(result.senses[0].definition.contains("children can play"))
        assertTrue(result.senses[1].definition.contains("enjoy themselves"))
    }

    @Test
    fun `parseHtml extracts UK IPA pronunciation`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val ukPron = result.pronunciations.find { it.region == "uk" }
        assertNotNull(ukPron)
        assertEquals("/ˈpleɪɡraʊnd/", ukPron.ipa)
    }

    @Test
    fun `parseHtml extracts US IPA pronunciation`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val usPron = result.pronunciations.find { it.region == "us" }
        assertNotNull(usPron)
        assertEquals("/ˈpleɪɡraʊnd/", usPron.ipa)
    }

    @Test
    fun `parseHtml extracts UK audio MP3 URL`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val ukPron = result.pronunciations.find { it.region == "uk" }
        assertNotNull(ukPron?.audioMp3Url)
        assertTrue(ukPron!!.audioMp3Url!!.contains("uk_pron"))
    }

    @Test
    fun `parseHtml extracts US audio MP3 URL`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val usPron = result.pronunciations.find { it.region == "us" }
        assertNotNull(usPron?.audioMp3Url)
        assertTrue(usPron!!.audioMp3Url!!.contains("us_pron"))
    }

    @Test
    fun `parseHtml extracts examples`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertTrue(result.examples.isNotEmpty())
        assertTrue(result.examples[0].contains("playground"))
    }

    @Test
    fun `parseHtml returns null for page with no dictionary content`() {
        val result = scraper.parseHtml("unknown", "https://test", noContentHtml)
        assertNull(result)
    }

    @Test
    fun `parseHtml sets correct source ID`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)
        assertNotNull(result)
        assertEquals(OxfordScraper.SOURCE_ID, result.source)
    }

    // ── Live integration tests ─────────────────────────────────────────────────

    @Ignore("Requires network — run manually with live Oxford server")
    @Test
    fun `live scrape returns definitions for playground`() {
        runBlocking {
            val result = scraper.scrape("playground")
            assertNotNull(result, "Expected non-null result for 'playground'")
            assertTrue(result!!.senses.isNotEmpty(), "Expected at least one sense")
            assertTrue(result.pronunciations.isNotEmpty(), "Expected at least one pronunciation")
        }
    }
}
