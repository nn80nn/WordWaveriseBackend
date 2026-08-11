package n.startapp.services.settings

import kotlinx.serialization.Serializable
import n.startapp.utils.EnvConfig

@Serializable
enum class SettingType { TEXT, INT, BOOL, ENUM }

/**
 * A setting the admin panel is allowed to change.
 *
 * An explicit catalogue rather than "anything in the environment": most variables are not safely
 * changeable while the process runs (database URL, JWT secret) and credentials must never make a
 * round trip through a browser. Everything here is a knob whose effect is observable and whose
 * worst case is reverting it.
 */
@Serializable
data class SettingSpec(
    val key: String,
    val label: String,
    val group: String,
    val type: SettingType,
    val default: String,
    val options: List<String> = emptyList(),
    val hint: String? = null
)

/** A setting together with what it currently resolves to and where that came from. */
@Serializable
data class SettingView(
    val spec: SettingSpec,
    val value: String,
    /** "override" — set here; "env" — from the deployment; "default" — nobody set it. */
    val source: String
)

/** Read-only facts about the deployment that the panel shows but must not edit. */
@Serializable
data class EnvironmentFact(
    val label: String,
    val value: String,
    val ok: Boolean
)

object SettingsCatalog {

    val specs: List<SettingSpec> = listOf(
        SettingSpec(
            key = "AI_MODEL",
            label = "Основная модель",
            group = "Модель",
            type = SettingType.TEXT,
            default = "llama-3.3-70b-versatile",
            hint = "Главный рычаг качества русского. Имя должно точно совпадать с тем, что отдаёт провайдер."
        ),
        SettingSpec(
            key = "AI_MODEL_FAST",
            label = "Быстрая модель",
            group = "Модель",
            type = SettingType.TEXT,
            default = "",
            hint = "Для резолва запроса. Пусто — берётся основная."
        ),
        SettingSpec(
            key = "AI_MODEL_POOL",
            label = "Модель резервного пула",
            group = "Модель",
            type = SettingType.TEXT,
            default = "",
            hint = "На ней идёт прогрев корпуса. Пусто — берётся основная."
        ),

        SettingSpec(
            key = "AI_TOKEN_PARAM",
            label = "Поле лимита токенов",
            group = "Совместимость провайдера",
            type = SettingType.ENUM,
            default = "max_tokens",
            options = listOf("max_tokens", "max_completion_tokens"),
            hint = "gpt-5.x требует max_completion_tokens. Исправляется само по первому отказу."
        ),
        SettingSpec(
            key = "AI_SUPPORTS_TEMPERATURE",
            label = "Отправлять temperature",
            group = "Совместимость провайдера",
            type = SettingType.BOOL,
            default = "true",
            hint = "Рассуждающие модели отвергают поле целиком."
        ),
        SettingSpec(
            key = "AI_STRUCTURED_MODE",
            label = "Режим структурированного ответа",
            group = "Совместимость провайдера",
            type = SettingType.ENUM,
            default = "json_schema",
            options = listOf("json_schema", "json_object", "none"),
            hint = "Деградирует сама, если провайдер не умеет."
        ),
        SettingSpec(
            key = "AI_TIMEOUT_MS",
            label = "Таймаут запроса, мс",
            group = "Совместимость провайдера",
            type = SettingType.INT,
            default = "150000",
            hint = "Полная статья пишется 1–3 минуты — ниже 60000 ставить нельзя."
        ),
        SettingSpec(
            key = "AI_MAX_RETRIES",
            label = "Повторов при сбое",
            group = "Совместимость провайдера",
            type = SettingType.INT,
            default = "2"
        ),

        SettingSpec(
            key = "WARMUP_ENABLED",
            label = "Прогрев при старте",
            group = "Прогрев",
            type = SettingType.BOOL,
            default = "false",
            hint = "Поднимать прогрев автоматически при рестарте контейнера."
        ),
        SettingSpec(
            key = "WARMUP_WORDS_PER_HOUR",
            label = "Слов в час",
            group = "Прогрев",
            type = SettingType.INT,
            default = "30",
            hint = "Скрейперы делят очередь с живыми запросами. Выше 60 — риск блокировки словарями."
        ),
        SettingSpec(
            key = "WARMUP_LIMIT",
            label = "Лимит слов",
            group = "Прогрев",
            type = SettingType.INT,
            default = "0",
            hint = "0 — весь список. Применяется на каждом рестарте."
        )
    )

    fun spec(key: String): SettingSpec? = specs.firstOrNull { it.key == key }

    fun view(): List<SettingView> = specs.map { spec ->
        val override = RuntimeSettings.override(spec.key)
        val fromEnv = System.getenv(spec.key)?.takeIf { it.isNotBlank() }
        SettingView(
            spec = spec,
            value = EnvConfig.get(spec.key, spec.default),
            source = when {
                override != null -> "override"
                fromEnv != null -> "env"
                else -> "default"
            }
        )
    }

    /**
     * Facts the panel shows but cannot change: credentials and endpoints stay with the
     * deployment. Presence is reported, values never are.
     */
    fun environment(): List<EnvironmentFact> = listOf(
        EnvironmentFact("Основной провайдер", EnvConfig.aiDomen.ifBlank { "не задан" }, EnvConfig.aiDomen.isNotBlank()),
        EnvironmentFact("Ключ основного", if (EnvConfig.aiApiKey.isNotBlank()) "задан" else "не задан", EnvConfig.aiApiKey.isNotBlank()),
        EnvironmentFact("Резервный пул", EnvConfig.aiDomenPool.ifBlank { "не задан" }, EnvConfig.aiDomenPool.isNotBlank()),
        EnvironmentFact("Ключ резервного", if (EnvConfig.aiApiKeyPool.isNotBlank()) "задан" else "не задан", EnvConfig.aiApiKeyPool.isNotBlank()),
        EnvironmentFact("WordsAPI", if (EnvConfig.get("WORDS_API_KEY").isNotBlank()) "задан" else "не задан (осознанно)", true),
        EnvironmentFact("Отправка почты", if (EnvConfig.resendApiKey.isNotBlank()) "задана" else "не задана", EnvConfig.resendApiKey.isNotBlank())
    )

    /** @return an error message, or null when the value is acceptable for this key. */
    fun validate(spec: SettingSpec, value: String): String? = when (spec.type) {
        SettingType.INT -> {
            val n = value.trim().toIntOrNull()
            when {
                n == null -> "Нужно целое число"
                n < 0 -> "Не может быть отрицательным"
                spec.key == "AI_TIMEOUT_MS" && n in 1..59_999 ->
                    "Меньше 60000 мс обрывает сборку статьи — она пишется 1–3 минуты"
                spec.key == "WARMUP_WORDS_PER_HOUR" && n > 240 ->
                    "Больше 240 в час словари не выдержат"
                else -> null
            }
        }
        SettingType.BOOL ->
            if (value.equals("true", true) || value.equals("false", true)) null
            else "Только true или false"
        SettingType.ENUM ->
            if (value in spec.options) null else "Допустимо: ${spec.options.joinToString(", ")}"
        SettingType.TEXT ->
            if (value.length <= 200) null else "Слишком длинное значение"
    }
}
