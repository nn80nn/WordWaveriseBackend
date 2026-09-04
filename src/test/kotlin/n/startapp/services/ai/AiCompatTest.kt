package n.startapp.services.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCompatTest {

    /** What a gateway translating `response_format` into an Anthropic tool actually returns. */
    private val forcedToolUse400 = """
        {"error":{"message":"{\n  \"error\": {\n    \"code\": 400,\n    \"message\": \"{\\\"type\\\":\\\"error\\\",\\\"error\\\":{\\\"type\\\":\\\"invalid_request_error\\\",\\\"message\\\":\\\"Thinking may not be enabled when tool_choice forces tool use.\\\"}}\",\n    \"status\": \"INVALID_ARGUMENT\"\n  }\n}\n","type":"upstream_error","code":400}}
    """.trimIndent()

    @Test
    fun `forced tool use is a complaint about response_format`() {
        assertTrue(AiCompat.blamesStructuredOutput(forcedToolUse400))
    }

    @Test
    fun `the field being named outright still counts`() {
        assertTrue(AiCompat.blamesStructuredOutput("""{"error":{"message":"response_format is not supported"}}"""))
        assertTrue(AiCompat.blamesStructuredOutput("""{"error":{"message":"Unsupported type: json_schema"}}"""))
    }

    @Test
    fun `unrelated rejections are left to the field adapter`() {
        assertFalse(AiCompat.blamesStructuredOutput("""{"error":{"message":"Use 'max_completion_tokens' instead of 'max_tokens'"}}"""))
        assertFalse(AiCompat.blamesStructuredOutput("""{"error":{"message":"temperature must be 1"}}"""))
        assertFalse(AiCompat.blamesStructuredOutput("""{"error":{"message":"model not found"}}"""))
    }
}
