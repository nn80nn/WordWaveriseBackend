package n.startapp.services.ai

import org.junit.Test
import kotlin.test.assertEquals

class LlmJsonTest {

    @Test
    fun `returns plain json unchanged`() {
        val raw = """{"a":1}"""
        assertEquals(raw, LlmJson.extract(raw))
    }

    @Test
    fun `strips json fenced block`() {
        val raw = "```json\n{\"a\":1}\n```"
        assertEquals("""{"a":1}""", LlmJson.extract(raw))
    }

    @Test
    fun `strips bare fenced block`() {
        val raw = "```\n{\"a\":1}\n```"
        assertEquals("""{"a":1}""", LlmJson.extract(raw))
    }

    @Test
    fun `drops leading prose`() {
        val raw = "Sure! Here is the JSON you asked for:\n{\"a\":1}"
        assertEquals("""{"a":1}""", LlmJson.extract(raw))
    }

    @Test
    fun `drops trailing commentary`() {
        val raw = "{\"a\":1}\n\nLet me know if you need anything else."
        assertEquals("""{"a":1}""", LlmJson.extract(raw))
    }

    @Test
    fun `keeps nested objects intact`() {
        val raw = """{"a":{"b":[1,2,{"c":3}]},"d":4}"""
        assertEquals(raw, LlmJson.extract(raw))
    }

    @Test
    fun `braces inside strings do not end the object`() {
        val raw = """{"note":"use {curly} braces","n":1}"""
        assertEquals(raw, LlmJson.extract(raw))
    }

    @Test
    fun `escaped quote inside string does not end the string`() {
        val raw = """{"note":"he said \"} \" here","n":1}"""
        assertEquals(raw, LlmJson.extract(raw))
    }

    @Test
    fun `extracts a top level array`() {
        val raw = "Here: [1, 2, 3] done"
        assertEquals("[1, 2, 3]", LlmJson.extract(raw))
    }

    @Test
    fun `unbalanced input falls through to the caller's parser`() {
        val raw = """{"a":1"""
        assertEquals(raw, LlmJson.extract(raw))
    }

    @Test
    fun `input with no json at all is returned trimmed`() {
        assertEquals("no json here", LlmJson.extract("  no json here  "))
    }
}
