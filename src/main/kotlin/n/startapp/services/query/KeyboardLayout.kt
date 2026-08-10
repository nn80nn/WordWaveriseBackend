package n.startapp.services.query

/**
 * Recovers text typed with the wrong keyboard layout — "ckjdj" is "слово" typed while the
 * layout was still English, and "цщквы" is "words" typed while it was still Russian.
 *
 * A frequent mistake for bilingual users and cheap to fix here: without it the input reaches
 * the dictionary as a nonsense headword and comes back as "not found".
 */
object KeyboardLayout {

    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~"
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбёЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЁ"

    private val enToRu = EN.zip(RU).toMap()
    private val ruToEn = RU.zip(EN).toMap()

    /** Latin input reinterpreted as Cyrillic, or null when the text is not pure Latin. */
    fun latinToCyrillic(text: String): String? = convert(text, enToRu) { it in 'a'..'z' || it in 'A'..'Z' }

    /** Cyrillic input reinterpreted as Latin, or null when the text is not pure Cyrillic. */
    fun cyrillicToLatin(text: String): String? = convert(text, ruToEn) { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

    private fun convert(text: String, table: Map<Char, Char>, isScriptLetter: (Char) -> Boolean): String? {
        if (text.isBlank()) return null
        val letters = text.count { it.isLetter() }
        if (letters == 0 || text.any { it.isLetter() && !isScriptLetter(it) }) return null

        val converted = buildString {
            for (c in text) {
                val mapped = table[c] ?: if (c.isLetter()) return null else c
                append(mapped)
            }
        }
        return converted.takeIf { it != text }
    }
}
