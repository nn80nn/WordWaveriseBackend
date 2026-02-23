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

class LdoceScraperTest {

    // Minimal HTML that mirrors the real LDOCE page structure for "playground"
    private val playgroundHtml = """
        <html><body>
        <div class="entry_content">
          <span class="dictentry">
            <span class="ldoceEntry Entry">
              <span class="frequent Head">
                <span class="HWD">playground</span>
                <span class="PronCodes">
                  <span class="neutral span"> /</span>
                  <span class="PRON">ˈpleɪɡraʊnd</span>
                  <span class="neutral span">/</span>
                </span>
                <span class="POS"> noun</span>
              </span>
              <span data-src-mp3="https://www.ldoceonline.com/media/english/breProns/playground0205.mp3"
                    class="speaker brefile fas fa-volume-up hideOnAmp"
                    title="Play British pronunciation of playground"> </span>
              <span data-src-mp3="https://www.ldoceonline.com/media/english/ameProns/playground.mp3"
                    class="speaker amefile fas fa-volume-up hideOnAmp"
                    title="Play American pronunciation of playground"> </span>
              <span class="Sense" id="playground__1">
                <span class="sensenum span">1</span>
                <span class="DEF">an area for children to play, especially at a school or in a park</span>
                <span class="GramExa">
                  <span class="EXAMPLE">children shouting and running in the playground</span>
                </span>
              </span>
              <span class="Sense" id="playground__2">
                <span class="sensenum span">2</span>
                <span class="DEF">a place where a particular group of people go to enjoy themselves</span>
              </span>
            </span>
          </span>
        </div>
        </body></html>
    """.trimIndent()

    private val noContentHtml = "<html><body><p>Page not found</p></body></html>"

    private val scraper = LdoceScraper(HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    })

    @Test
    fun `parseHtml extracts definitions from playground HTML`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertEquals("LDOCE", result.source)
        assertEquals("playground", result.word)
        assertEquals(2, result.senses.size)
        assertTrue(result.senses[0].definition.contains("children to play"))
        assertTrue(result.senses[1].definition.contains("enjoy themselves"))
    }

    @Test
    fun `parseHtml extracts UK pronunciation IPA`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertTrue(result.pronunciations.isNotEmpty())
        val ukPron = result.pronunciations.find { it.region == "uk" }
        assertNotNull(ukPron)
        assertEquals("/ˈpleɪɡraʊnd/", ukPron.ipa)
    }

    @Test
    fun `parseHtml extracts UK audio MP3 URL`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val ukPron = result.pronunciations.find { it.region == "uk" }
        assertNotNull(ukPron?.audioMp3Url)
        assertTrue(ukPron!!.audioMp3Url!!.contains("breProns"))
    }

    @Test
    fun `parseHtml extracts US audio MP3 URL`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val usPron = result.pronunciations.find { it.region == "us" }
        assertNotNull(usPron?.audioMp3Url)
        assertTrue(usPron!!.audioMp3Url!!.contains("ameProns"))
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
    fun `parseHtml returns null for blank HTML`() {
        val result = scraper.parseHtml("word", "https://test", "")
        assertNull(result)
    }

    @Test
    fun `parseHtml sets correct source ID`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)
        assertNotNull(result)
        assertEquals(LdoceScraper.SOURCE_ID, result.source)
    }

    // ── Live integration tests (require network, run with -Dlive=true) ─────────

    @Ignore("Requires network — run manually with live LDOCE server")
    @Test
    fun `live scrape returns definitions for playground`() {
        runBlocking {
            val result = scraper.scrape("playground")
            assertNotNull(result, "Expected non-null result for 'playground'")
            assertTrue(result!!.senses.isNotEmpty(), "Expected at least one sense")
            assertTrue(result.senses[0].definition.isNotBlank(), "Expected non-blank definition")
        }
    }

    @Ignore("Requires network — run manually with live LDOCE server")
    @Test
    fun `live scrape returns pronunciations for playground`() {
        runBlocking {
            val result = scraper.scrape("playground")
            assertNotNull(result)
            assertTrue(result!!.pronunciations.isNotEmpty(), "Expected at least one pronunciation")
            assertNotNull(result.pronunciations.find { it.region == "uk" }, "Expected UK pronunciation")
        }
    }
}
