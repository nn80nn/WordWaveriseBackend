package n.startapp.services.lexical

import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.SourceRef

/**
 * Builds the annotation prompt. Pure and side-effect free so it can be pinned by a golden test —
 * prompt drift is otherwise invisible until article quality quietly degrades.
 */
object LexicalPromptBuilder {

    /** Bump on any prompt change. Part of the persistent cache key. */
    const val PROMPT_VERSION = 4

    /**
     * The output contract, spelled out in the prompt as well as in `response_format`.
     *
     * Not redundant: providers that do not support `json_schema` are downgraded to plain
     * `json_object`, and a model told only "return JSON" invents its own field names — which
     * the validator then discards as empty, failing every annotation. The schema constrains
     * the reply where it is enforced; this makes the same shape reachable where it is not.
     */
    private val SHAPE = """
        Структура ответа — ровно такая, других полей быть не должно:

        {
          "lemma": "строка",
          "kind": "WORD" | "PHRASE" | "IDIOM" | "PHRASAL_VERB" | "ABBREVIATION" | "PROPER_NOUN",
          "etymology": "строка или null",
          "frequencyBand": "очень частотное" | "частотное" | "редкое" | null,
          "usageNotes": ["строка", ...],
          "posGroups": [
            {
              "pos": ${ALLOWED_POS.joinToString(" | ") { "\"$it\"" }},
              "posRu": "название части речи по-русски",
              "forms": {
                "plural": "строка или null", "past": "строка или null",
                "pastParticiple": "строка или null", "presentParticiple": "строка или null",
                "thirdPerson": "строка или null", "comparative": "строка или null",
                "superlative": "строка или null"
              },
              "senses": [
                {
                  "definitionEn": "определение по-английски",
                  "definitionRu": "объяснение по-русски одним предложением",
                  "translationsRu": ["1-4 коротких русских эквивалента"],
                  "register": "neutral" | "formal" | "informal" | "slang" | "vulgar" | "dated" | "literary" | "technical",
                  "countability": "countable" | "uncountable" | "both" | null,
                  "cefr": "A1" | "A2" | "B1" | "B2" | "C1" | "C2" | null,
                  "domain": "строка или null",
                  "examples": [{ "en": "предложение", "ru": "перевод", "sourceRef": число или null }],  // 1-2 штуки
                  "collocations": [{ "pattern": "строка", "ru": "строка или null" }],
                  "synonyms": ["строка", ...],
                  "antonyms": ["строка", ...],
                  "sourceRefs": [числа],
                  "generated": true | false,
                  "usageNote": "строка или null"
                }
              ]
            }
          ]
        }

        Обязательны и никогда не пустые: definitionEn, definitionRu, translationsRu (минимум 1),
        examples (1–2 штуки, с переводом). Значение без русского перевода будет отброшено.

        countability заполняется ТОЛЬКО для существительных (pos = "noun"), у остальных частей
        речи там строго null. Значение относится именно к этому смыслу, а не к слову целиком:
        "paper" как материал — uncountable, "paper" как документ — countable. "both" ставится,
        когда слово в этом же значении употребляется и так и так (например "coffee": "два кофе"
        и "много кофе"). Если не уверен — null; неверная пометка хуже отсутствующей.

        Пиши компактно: два примера на значение достаточно, третий не нужен.
    """.trimIndent()

    /** Guard rails for how much raw material is worth sending. */
    const val MAX_FRAGMENTS_PER_SOURCE = 12
    const val MAX_FRAGMENTS_TOTAL = 40

    private val SYSTEM_GROUNDED = """
        Ты — лексикограф, который составляет англо-русскую словарную статью для русскоязычных
        изучающих английский язык. Отвечай строго в формате JSON по заданной схеме.

        Тебе дают заголовочное слово и пронумерованный список СЫРЫХ фрагментов определений,
        собранных парсерами с нескольких словарных сайтов. Фрагменты шумные: дубликаты,
        обрезанные строки, иногда неверно размеченная часть речи, иногда посторонний мусор.

        Твоя задача — собрать из них ОДНУ чистую, хорошо организованную статью.

        Жёсткие правила:
        1. Каждое значение должно опираться на фрагменты. Для каждого значения указывай
           sourceRefs — номера фрагментов, на которых оно основано. Номера брать только из
           присланного списка.
        2. Ты можешь добавить значение, которого нет ни в одном фрагменте, ТОЛЬКО если это
           действительно распространённое значение, с которым столкнётся учащийся. Такое
           значение помечай "generated": true и "sourceRefs": [].
        3. НИКОГДА не выдумывай транскрипцию, IPA, ссылки на аудио, URL, даты и цитаты.
           В схеме нет таких полей — не добавляй их.
        4. Русский язык должен быть естественным и современным. translationsRu — 1–4 коротких
           эквивалента, которые русский человек реально сказал бы именно для ЭТОГО значения.
           definitionRu — полное объяснение одним предложением по-русски, а НЕ дословная
           калька английского определения.
        5. Объединяй дубликаты: если несколько фрагментов говорят об одном и том же — это
           ОДНО значение с несколькими sourceRefs.
        6. Внутри каждой части речи упорядочивай значения от самого частотного к самому
           узкому.
        7. Каждый пример — естественное полное предложение, содержащее заголовочное слово
           или его словоформу, обязательно с переводом на русский.
        8. Часть речи — только из списка схемы. Если фрагмент размечен неверно, исправь.
        8a. Для каждого значения существительного проставь countability (countable /
           uncountable / both). Для не-существительных — null.
        9. Если заголовок — фраза или идиома, сделай одну группу с pos "idiom" или "phrase"
           и НЕ разбивай её по частям речи входящих слов.
        10. Никакого markdown, никаких пояснений вне JSON.
    """.trimIndent()

    private val SYSTEM_UNGROUNDED = """
        Ты — лексикограф, который составляет англо-русскую словарную статью для русскоязычных
        изучающих английский язык. Отвечай строго в формате JSON по заданной схеме.

        Ни один словарь-источник не содержит этого выражения. Составь статью самостоятельно,
        опираясь на собственные знания языка.

        Жёсткие правила:
        1. Источников нет: каждому значению проставь "generated": true и "sourceRefs": [].
        2. Если выражение тебе незнакомо и ты не уверен в его значении — верни пустой
           массив posGroups. Пустая статья лучше выдуманной.
        3. НИКОГДА не выдумывай транскрипцию, IPA, ссылки на аудио, URL, даты и цитаты.
           В схеме нет таких полей — не добавляй их.
        4. Русский язык должен быть естественным и современным. translationsRu — 1–4 коротких
           эквивалента для ЭТОГО значения. definitionRu — объяснение одним предложением
           по-русски, а не калька английского определения.
        5. Внутри каждой части речи упорядочивай значения от частотного к узкому.
        6. Каждый пример — естественное полное предложение с переводом на русский.
        7. Часть речи — только из списка схемы. Для идиом и фраз используй одну группу
           с pos "idiom" или "phrase".
        7a. Для значений существительных проставь countability, для остальных — null.
        8. Никакого markdown, никаких пояснений вне JSON.
    """.trimIndent()

    fun system(grounded: Boolean): String =
        (if (grounded) SYSTEM_GROUNDED else SYSTEM_UNGROUNDED) + "\n\n" + SHAPE

    /**
     * Trims the raw definition list down to what is worth paying for, preserving source variety:
     * a per-source cap first, so one verbose source cannot crowd the others out of the budget.
     */
    fun selectFragments(all: List<SourceRef>): List<SourceRef> {
        val perSource = mutableMapOf<String, Int>()
        return all
            .filter { it.definition.isNotBlank() }
            .filter { ref ->
                val count = perSource.getOrDefault(ref.source, 0)
                if (count >= MAX_FRAGMENTS_PER_SOURCE) false
                else { perSource[ref.source] = count + 1; true }
            }
            .take(MAX_FRAGMENTS_TOTAL)
            // Renumber so indices are contiguous and match what the model is shown.
            .mapIndexed { i, ref -> ref.copy(index = i + 1) }
    }

    /**
     * Every part of speech present in the fragments, in the order a dictionary lists them.
     *
     * Used to split one large article into independent per-POS calls: the sections do not depend
     * on each other, so writing them concurrently turns the slowest term from a sum into a max.
     */
    fun partsOfSpeech(fragments: List<SourceRef>): List<String> {
        val order = ALLOWED_POS.withIndex().associate { (i, pos) -> pos to i }
        return fragments
            .mapNotNull { it.partOfSpeech?.trim()?.lowercase()?.takeIf { pos -> pos in order } }
            .distinct()
            .sortedBy { order[it] ?: order.size }
    }

    fun user(
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        fragments: List<SourceRef>,
        synonyms: List<String> = emptyList(),
        antonyms: List<String> = emptyList(),
        /** Restrict the reply to a single part of speech, so sections can be written in parallel. */
        onlyPos: String? = null,
        /** Only one of the parallel calls needs to answer the entry-level questions. */
        includeEntryLevel: Boolean = true
    ): String = buildString {
        appendLine("ЗАГОЛОВОК: $lemma")
        if (queryForm.isNotBlank() && !queryForm.equals(lemma, ignoreCase = true)) {
            appendLine("ФОРМА ЗАПРОСА: $queryForm")
        }
        appendLine("ТИП: ${kind.name}")

        val posSeen = fragments.mapNotNull { it.partOfSpeech?.trim()?.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (posSeen.isNotEmpty()) {
            appendLine("ЧАСТИ РЕЧИ, ВСТРЕЧЕННЫЕ В ИСТОЧНИКАХ: ${posSeen.joinToString(", ")}")
        }

        if (fragments.isEmpty()) {
            appendLine()
            appendLine("СЫРЫХ ФРАГМЕНТОВ НЕТ.")
        } else {
            appendLine()
            appendLine("СЫРЫЕ ФРАГМЕНТЫ:")
            fragments.forEach { ref ->
                val pos = ref.partOfSpeech?.trim()?.takeIf { it.isNotBlank() } ?: "?"
                appendLine("${ref.index}. [${ref.source} | $pos] ${ref.definition.trim()}")
                ref.example?.trim()?.takeIf { it.isNotBlank() }?.let {
                    appendLine("   пример: \"$it\"")
                }
            }
        }

        if (synonyms.isNotEmpty()) {
            appendLine()
            appendLine("СИНОНИМЫ ИЗ ИСТОЧНИКОВ: ${synonyms.take(20).joinToString(", ")}")
        }
        if (antonyms.isNotEmpty()) {
            appendLine("АНТОНИМЫ ИЗ ИСТОЧНИКОВ: ${antonyms.take(20).joinToString(", ")}")
        }

        if (onlyPos != null) {
            appendLine()
            appendLine("ЗАДАНИЕ: опиши ТОЛЬКО часть речи \"$onlyPos\".")
            appendLine("В posGroups верни РОВНО ОДНУ группу с pos = \"$onlyPos\".")
            appendLine("Фрагменты других частей речи игнорируй — их описывает другой запрос.")
            if (!includeEntryLevel) {
                appendLine("etymology, usageNotes и frequencyBand верни пустыми (null / []).")
            }
        }
    }.trimEnd()

    /** Appended on the retry attempt so the model is told what the validator objected to. */
    fun repairSuffix(issues: List<String>): String = buildString {
        appendLine()
        appendLine()
        appendLine("ПРЕДЫДУЩИЙ ОТВЕТ ОТКЛОНЁН ВАЛИДАТОРОМ.")
        appendLine("Проблемы:")
        issues.take(10).forEach { appendLine("- $it") }
        appendLine("Верни исправленный JSON по той же схеме. Ничего не выдумывай.")
    }.trimEnd()
}
