package n.startapp.models.dictionary

import kotlinx.serialization.Serializable

/**
 * Одна подсказка под строкой поиска.
 *
 * ⚠️ Голая строка подсказкой в словаре не работает. Список из шести английских слов не говорит,
 * какое из них человек имел в виду, — он заставляет открыть их по очереди, то есть делает
 * ровно ту работу, ради экономии которой подсказки и существуют. Перевод и часть речи здесь
 * стоят дёшево (у слова из корпуса они уже написаны) и превращают список в выбор.
 */
@Serializable
data class SuggestItem(
    val word: String,
    /** 1–2 русских эквивалента — только у слова, чья статья уже есть в корпусе. */
    val translation: String? = null,
    /** «существительное», «глагол» — как их называет статья. */
    val partOfSpeech: String? = null,
    /**
     * Откуда взялась строка, и это разные предложения человеку:
     * `corpus` — «статья готова, открывается мгновенно»,
     * `autocomplete` — «слово, которое так начинается»,
     * `spelling` — «возможно, вы имели в виду»,
     * `translation` — английский вариант для русского запроса.
     */
    val kind: String = KIND_AUTOCOMPLETE,
    /** Статья лежит в корпусе: открытие не пойдёт в словари и не займёт минуту. */
    val inCorpus: Boolean = false
) {
    companion object {
        const val KIND_CORPUS = "corpus"
        const val KIND_AUTOCOMPLETE = "autocomplete"
        const val KIND_SPELLING = "spelling"
        const val KIND_TRANSLATION = "translation"
    }
}

/**
 * Response for /api/words/suggest endpoint.
 * Returns spelling corrections (English) or translation candidates (Russian input).
 */
@Serializable
data class SuggestResponse(
    val query: String,
    val lang: String,                       // "en" | "ru"
    /**
     * Те же подсказки голыми строками.
     *
     * ⚠️ Остаётся ради установленных сборок приложения: они разбирают этот список и про
     * [items] ничего не знают. Выводится из [items], а не собирается отдельно, — иначе два
     * списка на одном экране рано или поздно разойдутся.
     */
    val suggestions: List<String> = emptyList(),
    /** Подсказки с переводом и частью речи — то, что рисуют оба клиента. */
    val items: List<SuggestItem> = emptyList(),
    /**
     * Russian input only: the same options with the context needed to choose between them.
     *
     * Added alongside [suggestions] rather than replacing it — existing app builds parse the
     * string list and ignore unknown keys, so they keep working and simply get better strings.
     */
    val candidates: List<RuEnCandidate> = emptyList()
)
