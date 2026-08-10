package n.startapp.services.ai

/**
 * Pulls the JSON payload out of a model reply.
 *
 * Even with a response-format constraint, providers occasionally wrap the answer in a fenced
 * block or prepend a sentence of prose. This used to be handled by ad-hoc `removePrefix("```json")`
 * chains at each call site, which broke on trailing commentary and on braces inside strings.
 */
object LlmJson {

    /**
     * @return the outermost balanced JSON object or array, or the trimmed input when no
     *         balanced structure is found (so the caller's parser produces the real error).
     */
    fun extract(raw: String): String {
        val text = stripFences(raw.trim())
        return extractBalanced(text) ?: text
    }

    private fun stripFences(text: String): String {
        if (!text.startsWith("```")) return text
        // Drop the opening fence line (```json / ```JSON / ```) and the closing fence.
        val afterOpen = text.substringAfter('\n', missingDelimiterValue = "")
        if (afterOpen.isEmpty()) return text
        val closeIndex = afterOpen.lastIndexOf("```")
        return if (closeIndex >= 0) afterOpen.substring(0, closeIndex).trim() else afterOpen.trim()
    }

    private fun extractBalanced(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
