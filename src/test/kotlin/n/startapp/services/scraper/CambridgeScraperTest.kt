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

class CambridgeScraperTest {

    // Minimal HTML mirroring real Cambridge dictionary structure for "playground"
    private val playgroundHtml = """
        <html><body>
        <div class="entry-body">
          <div class="dsense dsense-n">
            <div class="pos-header dpos-h">
              <span class="uk dpron-i ">
                <span class="region dreg">uk</span>
                <span class="daud">
                  <audio>
                    <source type="audio/mpeg" src="/media/english/uk_pron/playground_uk.mp3"/>
                  </audio>
                </span>
                <span class="pron dpron">/<span class="ipa dipa">ˈpleɪ.ɡraʊnd</span>/</span>
              </span>
              <span class="us dpron-i ">
                <span class="region dreg">us</span>
                <span class="daud">
                  <audio>
                    <source type="audio/mpeg" src="/media/english/us_pron/playground_us.mp3"/>
                  </audio>
                </span>
                <span class="pron dpron">/<span class="ipa dipa">ˈpleɪ.ɡraʊnd</span>/</span>
              </span>
              <div class="posgram dpos-g">
                <span class="pos dpos">noun</span>
              </div>
            </div>
            <div class="dsense-body">
              <div class="def-block ddef_block">
                <div class="ddef_h">
                  <div class="def ddef_d db">an area where children can play, especially at school or in a park</div>
                </div>
                <div class="def-body ddef_b">
                  <span class="eg deg">the school playground</span>
                </div>
              </div>
              <div class="def-block ddef_block">
                <div class="ddef_h">
                  <div class="def ddef_d db">a place where particular people like to go to enjoy themselves</div>
                </div>
                <div class="def-body ddef_b">
                  <span class="eg deg">a playground for the rich</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    private val noContentHtml = "<html><body><p>No results found</p></body></html>"

    private val scraper = CambridgeScraper(HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    })

    @Test
    fun `parseHtml extracts definitions from playground HTML`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertEquals("CAMBRIDGE", result.source)
        assertEquals("playground", result.word)
        assertTrue(result.senses.isNotEmpty())
        assertTrue(result.senses.any { it.definition.contains("children can play") })
    }

    @Test
    fun `parseHtml extracts UK pronunciation IPA`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val ukPron = result.pronunciations.find { it.region == "uk" }
        assertNotNull(ukPron)
        assertEquals("/ˈpleɪ.ɡraʊnd/", ukPron.ipa)
    }

    @Test
    fun `parseHtml extracts US pronunciation IPA`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val usPron = result.pronunciations.find { it.region == "us" }
        assertNotNull(usPron)
        assertEquals("/ˈpleɪ.ɡraʊnd/", usPron.ipa)
    }

    @Test
    fun `parseHtml extracts UK audio MP3 URL and makes it absolute`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        val ukPron = result.pronunciations.find { it.region == "uk" }
        assertNotNull(ukPron?.audioMp3Url)
        assertTrue(ukPron!!.audioMp3Url!!.startsWith("https://"))
        assertTrue(ukPron.audioMp3Url!!.contains("uk_pron"))
    }

    @Test
    fun `parseHtml extracts examples from definitions`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertTrue(result.examples.isNotEmpty())
    }

    @Test
    fun `parseHtml extracts part of speech`() {
        val result = scraper.parseHtml("playground", "https://test", playgroundHtml)

        assertNotNull(result)
        assertTrue(result.senses.any { it.pos == "noun" })
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
        assertEquals(CambridgeScraper.SOURCE_ID, result.source)
    }

    // ── Live integration tests ─────────────────────────────────────────────────

    @Ignore("Requires network — run manually with live Cambridge server")
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
