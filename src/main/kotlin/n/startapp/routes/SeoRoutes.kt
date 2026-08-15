package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import n.startapp.repositories.CorpusLemma
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.services.seo.WordPageRenderer
import n.startapp.utils.EnvConfig
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * The crawlable surface: one HTML page per headword, an A–Z index over them, and a sitemap
 * built from the corpus itself.
 *
 * These paths are served to users on the site's own origin — nginx proxies `/word/`, `/words`
 * and `/sitemap*.xml` here — so every link and canonical they emit names [EnvConfig.siteUrl].
 * The backend origin also answers them, which is why it ships a `robots.txt` that keeps
 * crawlers off it entirely; two hosts serving one page is a duplicate we would have to fix later.
 *
 * Nothing here can trigger a model call. A page exists only if the corpus already holds the
 * article, and a miss is an honest 404 — otherwise a crawler walking invented URLs would spend
 * the annotation budget and fill the index with thin pages.
 */

/** Marks a response as our own HTML so the JSON 404 handler leaves it alone. */
val SeoHtmlResponse = AttributeKey<Unit>("SeoHtmlResponse")

private const val URLS_PER_SITEMAP = 40_000
private val letters = ('a'..'z').map { it.toString() }
private val w3cDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

fun Route.seoRoutes(repository: LexicalEntryRepository) {
    val index = CorpusIndex(repository)

    get("/word/{lemma}") {
        val raw = call.parameters["lemma"].orEmpty().trim()
        val lemma = raw.lowercase()
        if (lemma.isBlank() || lemma.length > 80) {
            call.respondSeoHtml(WordPageRenderer.missingPage(raw), HttpStatusCode.NotFound)
            return@get
        }

        // One page per word, one URL per page: casing and inflections redirect to the headword
        // rather than rendering a second copy of the same article.
        if (raw != lemma) {
            call.respondRedirect(WordPageRenderer.wordPath(lemma), permanent = true)
            return@get
        }

        val entry = repository.findLatestByLemma(lemma)
        if (entry == null) {
            val canonicalLemma = repository.findLemmaByForm(lemma)
            if (canonicalLemma != null && canonicalLemma != lemma) {
                call.respondRedirect(WordPageRenderer.wordPath(canonicalLemma), permanent = true)
                return@get
            }
            call.respondSeoHtml(WordPageRenderer.missingPage(raw), HttpStatusCode.NotFound)
            return@get
        }

        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600, stale-while-revalidate=86400")
        call.respondSeoHtml(WordPageRenderer.wordPage(entry))
    }

    get("/words") {
        val counts = index.letterCounts()
        val total = counts.sumOf { it.second }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondSeoHtml(WordPageRenderer.alphabetPage(counts, total))
    }

    get("/words/{letter}") {
        val letter = call.parameters["letter"].orEmpty().trim().lowercase()
        if (letter !in letters) {
            call.respondSeoHtml(WordPageRenderer.missingPage(letter), HttpStatusCode.NotFound)
            return@get
        }
        val lemmas = repository.lemmasStartingWith(letter)
        if (lemmas.isEmpty()) {
            call.respondSeoHtml(WordPageRenderer.missingPage(letter), HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondSeoHtml(WordPageRenderer.letterPage(letter, lemmas))
    }

    // ── Sitemaps ────────────────────────────────────────────────────────────

    get("/sitemap.xml") {
        val chunks = (index.lemmas().size + URLS_PER_SITEMAP - 1) / URLS_PER_SITEMAP
        val site = EnvConfig.siteUrl
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
            // The marketing pages are a static file on the web container; the words are ours.
            append("<sitemap><loc>$site/sitemap-pages.xml</loc></sitemap>")
            repeat(maxOf(chunks, 1)) { i ->
                append("<sitemap><loc>$site/sitemap-words-${i + 1}.xml</loc></sitemap>")
            }
            append("</sitemapindex>")
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(xml, ContentType.Text.Xml)
    }

    get("/sitemap-words-{n}.xml") {
        val page = call.parameters["n"]?.toIntOrNull() ?: 1
        val all = index.lemmas()
        val slice = all.drop((page - 1) * URLS_PER_SITEMAP).take(URLS_PER_SITEMAP)
        val site = EnvConfig.siteUrl
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
            append("<url><loc>$site/words</loc><changefreq>daily</changefreq><priority>0.7</priority></url>")
            if (page == 1) {
                letters.forEach { append("<url><loc>$site/words/$it</loc><changefreq>weekly</changefreq></url>") }
            }
            slice.forEach { row ->
                append("<url><loc>$site${WordPageRenderer.wordPath(row.lemma)}</loc>")
                if (row.updatedAt > 0) {
                    append("<lastmod>${w3cDate.format(Instant.ofEpochMilli(row.updatedAt))}</lastmod>")
                }
                append("<changefreq>monthly</changefreq><priority>0.6</priority></url>")
            }
            append("</urlset>")
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(xml, ContentType.Text.Xml)
    }

    /**
     * The API origin is not a place to index. Crawlers reach these same pages through the site,
     * where the site's own robots.txt applies; this only keeps the duplicate host out.
     */
    get("/robots.txt") {
        call.respondText("User-agent: *\nDisallow: /\n", ContentType.Text.Plain)
    }
}

private suspend fun ApplicationCall.respondSeoHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    attributes.put(SeoHtmlResponse, Unit)
    respondText(html, ContentType.Text.Html, status)
}

/**
 * The lemma list, cached for a few minutes.
 *
 * `publishedLemmas()` groups over the whole table, and the sitemap chunks each need it. The
 * corpus only grows at warm-up pace, so a stale-by-minutes list costs nothing and a crawler
 * pulling twenty sitemap chunks does not turn into twenty full scans.
 */
private class CorpusIndex(private val repository: LexicalEntryRepository) {
    private val ttlMs = 5 * 60 * 1000L
    private val cached = AtomicReference<Pair<Long, List<CorpusLemma>>?>(null)

    suspend fun lemmas(): List<CorpusLemma> {
        val now = System.currentTimeMillis()
        cached.get()?.let { (at, list) -> if (now - at < ttlMs) return list }
        val fresh = repository.publishedLemmas()
        cached.set(now to fresh)
        return fresh
    }

    suspend fun letterCounts(): List<Pair<String, Int>> {
        val byLetter = lemmas().groupingBy { it.lemma.take(1).lowercase() }.eachCount()
        return letters.map { it to (byLetter[it] ?: 0) }
    }
}
