package n.startapp.services.seo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Register
import n.startapp.models.lexical.Sense
import n.startapp.utils.EnvConfig
import java.net.URLEncoder

/**
 * Renders the corpus as plain, crawlable HTML.
 *
 * The SPA cannot do this job: it ships an empty `<div id="app">` and paints the article from JS,
 * so every word in the corpus is one URL — `/search?q=` — as far as a crawler is concerned.
 * These pages exist to give each headword its own address with the text already in the markup.
 *
 * Deliberately standalone: no bundle, no font CDN, no hydration. The renderer runs in the
 * backend, which has no way to learn the SPA's content-hashed asset names, and a page that
 * needs nothing but itself is also the fastest thing we can hand a crawler.
 */
object WordPageRenderer {

    private val registerRu = mapOf(
        Register.NEUTRAL to "",
        Register.FORMAL to "формальное",
        Register.INFORMAL to "разговорное",
        Register.SLANG to "сленг",
        Register.VULGAR to "грубое",
        Register.DATED to "устаревшее",
        Register.LITERARY to "книжное",
        Register.TECHNICAL to "спец."
    )

    // ── Public entry points ─────────────────────────────────────────────────

    fun wordPage(entry: LexicalEntry): String {
        val lemma = entry.lemma
        val translations = entry.posGroups
            .flatMap { it.senses }
            .flatMap { it.translationsRu }
            .distinct()

        val title = "$lemma — перевод, произношение и примеры | WordWaverise"
        val description = buildString {
            append("$lemma — ")
            if (translations.isNotEmpty()) append(translations.take(6).joinToString(", ")).append(". ")
            entry.posGroups.firstOrNull()?.senses?.firstOrNull()
                ?.let { append(it.definitionRu.take(150)).append(" ") }
            append("Значения, транскрипция, примеры с переводом.")
        }.take(300)

        val body = buildString {
            append(header(lemma))
            append("<main class=\"wrap\">")
            append("<article class=\"card\">")
            append(articleHead(entry, translations))
            entry.posGroups.forEach { append(posSection(it)) }
            append(extras(entry))
            append(sources(entry))
            append("</article>")
            append(appCta(lemma))
            append(alsoSee(entry))
            append("</main>")
            append(footer())
        }

        return document(
            title = title,
            description = description,
            canonical = "${EnvConfig.siteUrl}${wordPath(lemma)}",
            extraHead = jsonLd(wordJsonLd(entry, description)),
            body = body
        )
    }

    /** A–Z hub: the one page that links into every letter, so the corpus is reachable by crawl. */
    fun alphabetPage(letters: List<Pair<String, Int>>, total: Int): String {
        val body = buildString {
            append(header(null))
            append("<main class=\"wrap\">")
            append("<article class=\"card\">")
            append("<h1>Англо-русский словарь</h1>")
            append(
                "<p class=\"lead\">В словаре WordWaverise ${plural(total, "статья", "статьи", "статей")}" +
                    " с переводом на русский, транскрипцией, примерами и синонимами." +
                    " Выберите букву, чтобы посмотреть слова.</p>"
            )
            append("<nav class=\"letters\">")
            letters.forEach { (letter, count) ->
                if (count == 0) {
                    append("<span class=\"letter off\">${esc(letter.uppercase())}</span>")
                } else {
                    append(
                        "<a class=\"letter\" href=\"/words/${esc(letter)}\">" +
                            "${esc(letter.uppercase())}<small>$count</small></a>"
                    )
                }
            }
            append("</nav>")
            append("</article>")
            append("</main>")
            append(footer())
        }
        return document(
            title = "Англо-русский словарь онлайн — все слова | WordWaverise",
            description = "Полный список слов англо-русского словаря WordWaverise: " +
                "$total статей с переводом, транскрипцией и примерами употребления.",
            canonical = "${EnvConfig.siteUrl}/words",
            extraHead = "",
            body = body
        )
    }

    fun letterPage(letter: String, lemmas: List<String>): String {
        val up = letter.uppercase()
        val body = buildString {
            append(header(null))
            append("<main class=\"wrap\">")
            append("<article class=\"card\">")
            append(breadcrumbs(listOf("Словарь" to "/words", up to null)))
            append("<h1>Слова на букву $up</h1>")
            append(
                "<p class=\"lead\">${plural(lemmas.size, "слово", "слова", "слов")} на букву $up" +
                    " с переводом на русский язык.</p>"
            )
            append("<ul class=\"index\">")
            lemmas.forEach { append("<li><a href=\"${esc(wordPath(it))}\">${esc(it)}</a></li>") }
            append("</ul>")
            append("</article>")
            append("</main>")
            append(footer())
        }
        return document(
            title = "Английские слова на букву $up — перевод на русский | WordWaverise",
            description = "Список английских слов на букву $up с переводом, транскрипцией " +
                "и примерами. Всего ${lemmas.size} слов в словаре WordWaverise.",
            canonical = "${EnvConfig.siteUrl}/words/${letter.lowercase()}",
            extraHead = "",
            body = body
        )
    }

    /**
     * A word we have no article for. Served with 404 on purpose: promising a page for every
     * string a crawler can invent is how a dictionary drowns in thin pages.
     */
    fun missingPage(query: String): String {
        val body = buildString {
            append(header(null))
            append("<main class=\"wrap\">")
            append("<article class=\"card\">")
            append("<h1>Статья пока не готова</h1>")
            append(
                "<p class=\"lead\">Слова «${esc(query)}» ещё нет в словаре. " +
                    "Попробуйте найти его через поиск — статья соберётся за несколько секунд.</p>"
            )
            append("<p><a class=\"cta\" href=\"/search?q=${urlEncode(query)}\">Найти «${esc(query)}»</a></p>")
            append("<p><a href=\"/words\">Все слова словаря →</a></p>")
            append("</article>")
            append("</main>")
            append(footer())
        }
        return document(
            title = "Слово не найдено | WordWaverise",
            description = "Статья ещё не готова.",
            canonical = null,
            extraHead = "<meta name=\"robots\" content=\"noindex, follow\">",
            body = body
        )
    }

    // ── Page pieces ─────────────────────────────────────────────────────────

    private fun articleHead(entry: LexicalEntry, translations: List<String>): String = buildString {
        val initial = entry.lemma.take(1)
        append(
            breadcrumbs(
                listOf(
                    "Словарь" to "/words",
                    initial.uppercase() to "/words/${initial.lowercase()}",
                    entry.lemma to null
                )
            )
        )
        append("<h1>${esc(entry.lemma)}</h1>")

        val prons = (entry.pronunciations + entry.posGroups.flatMap { it.pronunciations })
            .filter { !it.ipa.isNullOrBlank() }
            .distinctBy { it.region to it.ipa }
        if (prons.isNotEmpty()) {
            append("<p class=\"ipa\">")
            append(prons.joinToString(" · ") { p ->
                val region = p.region?.takeIf { it.isNotBlank() }
                    ?.let { "<span class=\"tag\">${esc(it.uppercase())}</span> " } ?: ""
                region + esc(p.ipa!!)
            })
            append("</p>")
        } else if (!entry.phonetic.isNullOrBlank()) {
            append("<p class=\"ipa\">${esc(entry.phonetic!!)}</p>")
        }

        val audio = entry.audioUrl?.takeIf { it.isNotBlank() }
            ?: prons.firstNotNullOfOrNull { it.audioMp3Url?.takeIf { url -> url.isNotBlank() } }
        if (audio != null) {
            append("<audio class=\"audio\" controls preload=\"none\" src=\"${esc(audio)}\"></audio>")
        }

        if (translations.isNotEmpty()) {
            append(
                "<p class=\"lead\"><strong>Перевод:</strong> " +
                    "${esc(translations.take(8).joinToString(", "))}</p>"
            )
        }
    }

    private fun posSection(group: PosGroup): String = buildString {
        append("<section class=\"pos\">")
        append(
            "<h2>${esc(group.posRu.ifBlank { group.pos })}" +
                "<span class=\"tag\">${esc(group.pos)}</span></h2>"
        )

        group.forms?.let { f ->
            val parts = listOfNotNull(
                f.plural?.let { "мн. ч. — $it" },
                f.past?.let { "прош. вр. — $it" },
                f.pastParticiple?.let { "прич. II — $it" },
                f.presentParticiple?.let { "прич. I — $it" },
                f.thirdPerson?.let { "3-е л. — $it" },
                f.comparative?.let { "сравн. — $it" },
                f.superlative?.let { "превосх. — $it" }
            )
            if (parts.isNotEmpty()) append("<p class=\"forms\">${esc(parts.joinToString(" · "))}</p>")
        }

        append("<ol class=\"senses\">")
        group.senses.forEach { append(senseItem(it)) }
        append("</ol>")
        append("</section>")
    }

    private fun senseItem(sense: Sense): String = buildString {
        append("<li id=\"${esc(sense.id)}\">")

        val badges = buildString {
            registerRu[sense.register]?.takeIf { it.isNotBlank() }
                ?.let { append("<span class=\"tag\">${esc(it)}</span>") }
            sense.cefr?.takeIf { it.isNotBlank() }
                ?.let { append("<span class=\"tag\">${esc(it)}</span>") }
            sense.domain?.takeIf { it.isNotBlank() }
                ?.let { append("<span class=\"tag\">${esc(it)}</span>") }
            // Honest labelling: a sense no source supported is the model's, and says so.
            if (sense.generated) append("<span class=\"tag ai\">ИИ</span>")
        }
        if (badges.isNotEmpty()) append("<p class=\"badges\">$badges</p>")

        if (sense.translationsRu.isNotEmpty()) {
            append("<p class=\"tr\">${esc(sense.translationsRu.joinToString(", "))}</p>")
        }
        append("<p class=\"def-ru\">${esc(sense.definitionRu)}</p>")
        if (sense.definitionEn.isNotBlank()) {
            append("<p class=\"def-en\">${esc(sense.definitionEn)}</p>")
        }
        sense.usageNote?.takeIf { it.isNotBlank() }
            ?.let { append("<p class=\"note\">${esc(it)}</p>") }

        if (sense.examples.isNotEmpty()) {
            append("<ul class=\"examples\">")
            sense.examples.forEach {
                append(
                    "<li><span class=\"ex-en\">${esc(it.en)}</span>" +
                        "<span class=\"ex-ru\">${esc(it.ru)}</span></li>"
                )
            }
            append("</ul>")
        }

        if (sense.collocations.isNotEmpty()) {
            append(
                "<p class=\"colloc\">" + sense.collocations.joinToString(" · ") { c ->
                    esc(c.pattern) + (c.ru?.takeIf { it.isNotBlank() }?.let { " — ${esc(it)}" } ?: "")
                } + "</p>"
            )
        }

        append(relatedList("Синонимы", sense.synonyms))
        append(relatedList("Антонимы", sense.antonyms))
        append("</li>")
    }

    /** Synonyms become links: this is the internal linking that lets a crawler walk the corpus. */
    private fun relatedList(label: String, words: List<String>): String {
        val usable = words.map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
            .take(10)
        if (usable.isEmpty()) return ""
        return "<p class=\"related\"><span class=\"rel-label\">$label:</span> " +
            usable.joinToString(", ") { "<a href=\"${esc(wordPath(it))}\">${esc(it)}</a>" } +
            "</p>"
    }

    private fun extras(entry: LexicalEntry): String = buildString {
        entry.etymology?.takeIf { it.isNotBlank() }?.let {
            append("<section class=\"pos\"><h2>Происхождение</h2><p>${esc(it)}</p></section>")
        }
        if (entry.usageNotes.isNotEmpty()) {
            append("<section class=\"pos\"><h2>Как употреблять</h2><ul class=\"plain\">")
            entry.usageNotes.forEach { append("<li>${esc(it)}</li>") }
            append("</ul></section>")
        }
    }

    private fun sources(entry: LexicalEntry): String {
        val names = entry.sources.map { it.source }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return ""
        return "<p class=\"sources\">Источники: ${esc(names.joinToString(", "))}</p>"
    }

    private fun appCta(lemma: String): String =
        "<section class=\"cta-box\">" +
            "<h2>Запомнить это слово</h2>" +
            "<p>Добавьте «${esc(lemma)}» в карточки — WordWaverise напомнит о нём тогда, " +
            "когда вы будете готовы его забыть.</p>" +
            "<a class=\"cta\" href=\"/search?q=${urlEncode(lemma)}\">Открыть в приложении</a>" +
            "</section>"

    private fun alsoSee(entry: LexicalEntry): String {
        val letter = entry.lemma.take(1).lowercase()
        return "<nav class=\"also\"><a href=\"/words/${esc(letter)}\">" +
            "Другие слова на букву ${esc(letter.uppercase())}</a> · " +
            "<a href=\"/words\">Весь словарь</a></nav>"
    }

    private fun header(lemma: String?): String =
        "<header class=\"top\"><div class=\"wrap row\">" +
            "<a class=\"brand\" href=\"/\">WordWaverise</a>" +
            "<form class=\"search\" action=\"/search\" method=\"get\" role=\"search\">" +
            "<input type=\"search\" name=\"q\" placeholder=\"Английское слово…\" " +
            "aria-label=\"Поиск по словарю\"" +
            (lemma?.let { " value=\"${esc(it)}\"" } ?: "") + ">" +
            "<button type=\"submit\">Найти</button>" +
            "</form></div></header>"

    private fun footer(): String =
        "<footer class=\"bottom\"><div class=\"wrap\">" +
            "<a href=\"/\">Главная</a> · <a href=\"/words\">Словарь</a> · " +
            "<a href=\"/privacy-policy\">Конфиденциальность</a> · <a href=\"/terms\">Условия</a>" +
            "<p class=\"fine\">© WordWaverise — англо-русский словарь с примерами и карточками.</p>" +
            "</div></footer>"

    private fun breadcrumbs(trail: List<Pair<String, String?>>): String =
        "<nav class=\"crumbs\">" + trail.joinToString(" / ") { (label, href) ->
            if (href == null) "<span>${esc(label)}</span>" else "<a href=\"${esc(href)}\">${esc(label)}</a>"
        } + "</nav>"

    // ── Structured data ─────────────────────────────────────────────────────

    private fun wordJsonLd(entry: LexicalEntry, description: String): JsonObject = buildJsonObject {
        put("@context", "https://schema.org")
        put("@type", "DefinedTerm")
        put("name", entry.lemma)
        put("inLanguage", "en")
        put("description", description)
        put("url", "${EnvConfig.siteUrl}${wordPath(entry.lemma)}")
        putJsonObject("inDefinedTermSet") {
            put("@type", "DefinedTermSet")
            put("name", "Англо-русский словарь WordWaverise")
            put("url", "${EnvConfig.siteUrl}/words")
        }
        val examples = entry.posGroups.flatMap { it.senses }.flatMap { it.examples }.take(5)
        if (examples.isNotEmpty()) {
            putJsonArray("subjectOf") {
                examples.forEach { ex ->
                    add(buildJsonObject {
                        put("@type", "CreativeWork")
                        put("text", ex.en)
                    })
                }
            }
        }
    }

    private fun jsonLd(obj: JsonObject): String =
        "<script type=\"application/ld+json\">" +
            // A literal `</script>` inside the payload would close the block early.
            obj.toString().replace("<", "\\u003c") +
            "</script>"

    // ── Shell ───────────────────────────────────────────────────────────────

    private fun document(
        title: String,
        description: String,
        canonical: String?,
        extraHead: String,
        body: String
    ): String = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
${canonical?.let { "<link rel=\"canonical\" href=\"${esc(it)}\">" } ?: ""}
<meta property="og:type" content="article">
<meta property="og:site_name" content="WordWaverise">
<meta property="og:locale" content="ru_RU">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
${canonical?.let { "<meta property=\"og:url\" content=\"${esc(it)}\">" } ?: ""}
<meta property="og:image" content="${EnvConfig.siteUrl}/logo.png">
<meta name="twitter:card" content="summary">
<link rel="icon" type="image/png" href="/logo.png">
$extraHead
<style>$CSS</style>
</head>
<body>
$body
</body>
</html>"""

    private const val CSS = """
:root{--bg:#FBF8F2;--card:#fff;--ink:#12262E;--dim:#5A6B72;--line:#E6DFD2;--accent:#0F7A6B;--tagbg:#EFEAE0;--on-accent:#FBF8F2}
@media (prefers-color-scheme:dark){:root{--bg:#04161E;--card:#0A222C;--ink:#E8F1F2;--dim:#93A7AE;--line:#153341;--accent:#4FD1B5;--tagbg:#133340;--on-accent:#04161E}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-text-size-adjust:100%}
a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
.wrap{max-width:760px;margin:0 auto;padding:0 20px}
.top{border-bottom:1px solid var(--line);padding:14px 0;position:sticky;top:0;background:var(--bg);z-index:5}
.row{display:flex;gap:16px;align-items:center;justify-content:space-between;flex-wrap:wrap}
.brand{font-weight:700;font-size:19px;color:var(--ink)}
.search{display:flex;gap:8px;flex:1;min-width:220px;max-width:420px}
.search input{flex:1;min-width:0;padding:9px 12px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:var(--ink);font-size:15px}
.search button{padding:9px 16px;border:0;border-radius:10px;background:var(--accent);color:var(--on-accent);font-weight:600;cursor:pointer}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:28px;margin:24px 0}
.crumbs{font-size:13px;color:var(--dim);margin-bottom:12px}
.crumbs a{color:var(--dim)}
h1{font-size:38px;line-height:1.15;margin:0 0 6px}
h2{font-size:19px;margin:28px 0 10px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.ipa{color:var(--dim);font-size:17px;margin:0 0 10px}
.audio{display:block;width:100%;max-width:320px;height:34px;margin:6px 0 12px}
.lead{font-size:17px;margin:10px 0 0}
.tag{display:inline-block;font-size:11px;letter-spacing:.04em;text-transform:uppercase;background:var(--tagbg);color:var(--dim);padding:2px 7px;border-radius:6px;font-weight:600}
.tag.ai{color:var(--accent)}
.badges{margin:0 0 6px;display:flex;gap:6px;flex-wrap:wrap}
.pos{border-top:1px solid var(--line);margin-top:24px}
.pos:first-of-type{border-top:0}
.forms{color:var(--dim);font-size:14px;margin:0 0 12px}
.senses{margin:0;padding-left:22px}
.senses>li{margin:0 0 26px}
.tr{font-weight:650;font-size:17px;margin:0 0 4px}
.def-ru{margin:0 0 4px}
.def-en{margin:0 0 8px;color:var(--dim);font-size:15px}
.note{margin:6px 0;font-size:14px;color:var(--dim);font-style:italic}
.examples{list-style:none;margin:10px 0;padding:0;border-left:2px solid var(--line)}
.examples li{padding:4px 0 4px 14px;margin-bottom:6px}
.ex-en{display:block}
.ex-ru{display:block;color:var(--dim);font-size:15px}
.colloc{font-size:14px;color:var(--dim);margin:8px 0}
.related{font-size:14px;margin:6px 0}
.rel-label{color:var(--dim)}
.plain{padding-left:20px}
.sources{margin-top:26px;padding-top:14px;border-top:1px solid var(--line);font-size:13px;color:var(--dim)}
.cta-box{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:24px;margin:24px 0}
.cta-box h2{margin-top:0}
.cta{display:inline-block;margin-top:8px;padding:11px 20px;border-radius:12px;background:var(--accent);color:var(--on-accent);font-weight:650}
.cta:hover{text-decoration:none;opacity:.9}
.also{font-size:14px;color:var(--dim);margin:8px 0 32px}
.letters{display:flex;flex-wrap:wrap;gap:10px;margin-top:20px}
.letter{display:flex;flex-direction:column;align-items:center;justify-content:center;width:62px;height:62px;border:1px solid var(--line);border-radius:12px;font-size:20px;font-weight:650}
.letter small{font-size:11px;color:var(--dim);font-weight:400}
.letter.off{color:var(--dim);opacity:.4}
.index{list-style:none;padding:0;margin:18px 0 0;columns:3;column-gap:24px}
.index li{margin-bottom:6px;break-inside:avoid}
.bottom{border-top:1px solid var(--line);padding:22px 0 40px;font-size:14px;color:var(--dim)}
.fine{margin:10px 0 0;font-size:13px}
@media(max-width:560px){h1{font-size:30px}.card{padding:20px}.index{columns:2}}
"""

    // ── Helpers ─────────────────────────────────────────────────────────────

    fun wordPath(lemma: String): String = "/word/" + urlEncode(lemma)

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value.trim().lowercase(), "UTF-8").replace("+", "%20")

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        val word = when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..3 -> few
            else -> many
        }
        return "$n $word"
    }
}
