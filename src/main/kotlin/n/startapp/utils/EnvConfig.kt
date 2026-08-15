package n.startapp.utils

import java.io.File

/**
 * Configuration loader for environment variables
 */
object EnvConfig {
    private val envMap = mutableMapOf<String, String>()

    init {
        loadEnvFile()
    }

    private fun loadEnvFile() {
        val envFile = File(System.getProperty("user.dir"), ".env")
        println("📁 Loading environment variables from .env file...")
        println("   File path: ${envFile.absolutePath}")
        println("   File exists: ${envFile.exists()}")

        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                    val parts = trimmedLine.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        envMap[key] = value
                        if (key.contains("PASSWORD")) {
                            println("   Loaded: $key=***")
                        } else {
                            println("   Loaded: $key=$value")
                        }
                    }
                }
            }
        } else {
            println("   ⚠️  .env file not found, using system environment variables")
        }
    }

    /**
     * A blank value counts as "not set" and falls through to [default].
     *
     * This matters for docker-compose: `- AI_MODEL=${AI_MODEL}` always defines the variable,
     * and it lands in the container as an empty string whenever Dokploy has no value for it.
     * Without this, an unset variable would beat its default and, for example, ship
     * `"model": ""` to the AI provider.
     *
     * A runtime override set from the admin panel wins over both files and process environment —
     * that is what "changed here" has to mean. Because every setting in the app is read through
     * this one function, intercepting it is the whole of the mechanism: no call site knows or
     * needs to know that a value can now change without a redeploy. Until the database is up the
     * override map is empty, so startup reads the deployment exactly as before.
     */
    fun get(key: String, default: String = ""): String {
        val value = n.startapp.services.settings.RuntimeSettings.override(key)?.takeIf { it.isNotBlank() }
            ?: envMap[key]?.takeIf { it.isNotBlank() }
            ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        return value ?: default
    }

    fun getInt(key: String, default: Int): Int {
        return get(key).toIntOrNull() ?: default
    }

    // Database configuration
    val dbUrl: String get() = get("DB_URL", "jdbc:postgresql://localhost:5432/wordwaverise")
    val dbUser: String get() = get("DB_USER", "postgres")
    val dbPassword: String get() = get("DB_PASSWORD", "postgres")
    val dbDriver: String get() = get("DB_DRIVER", "org.postgresql.Driver")

    // JWT configuration
    val jwtSecret: String get() = get("JWT_SECRET", "development-secret-key")
    val jwtIssuer: String get() = get("JWT_ISSUER", "wordwaverise-backend")
    val jwtAudience: String get() = get("JWT_AUDIENCE", "wordwaverise-app")
    val jwtRealm: String get() = get("JWT_REALM", "Access to WordWaverise API")
    val jwtExpirationHours: Int get() = getInt("JWT_EXPIRATION_HOURS", 720)

    // AI configuration
    val aiDomen: String get() = get("AI_DOMEN", "")
    val aiApiKey: String get() = get("AI_API", "")
    /**
     * Defaults describe the model production is known to run today. To move to a stronger model,
     * set AI_MODEL in Dokploy — do not change this default without confirming the provider
     * serves that exact name, since a wrong name 400s every AI call.
     */
    val aiModel: String get() = get("AI_MODEL", "llama-3.3-70b-versatile")

    /** Cheaper/faster model for auxiliary tasks (query resolution). Falls back to [aiModel]. */
    val aiModelFast: String get() = get("AI_MODEL_FAST", "").ifBlank { aiModel }

    /**
     * Name of the token-limit field in the chat-completions body.
     * gpt-5.x requires `max_completion_tokens`; older/OSS models want `max_tokens`.
     * The default matches [aiModel]; a mismatch is auto-corrected at runtime on the first
     * 400 — see [n.startapp.services.ai.AiCompat] — so switching AI_MODEL alone is safe.
     */
    val aiTokenParam: String get() = get("AI_TOKEN_PARAM", "max_tokens")

    /** gpt-5.x rejects the field outright; llama and most OSS models accept it. */
    val aiSupportsTemperature: Boolean get() = get("AI_SUPPORTS_TEMPERATURE", "true").equals("true", true)

    /**
     * Per-request ceiling. A full annotated article is thousands of tokens and measured at ~60s
     * on its own, so the old 60s default cut off the very call the layer exists for.
     */
    val aiTimeoutMs: Long get() = get("AI_TIMEOUT_MS", "150000").toLongOrNull() ?: 150_000L
    val aiMaxRetries: Int get() = getInt("AI_MAX_RETRIES", 2)

    // ── Reserve provider ────────────────────────────────────────────────────
    // A second endpoint with its own quota. Two jobs: it carries the corpus warm-up, so a bulk
    // run cannot spend the budget real lookups depend on, and it catches user-facing requests
    // when the primary is rate limited.
    val aiDomenPool: String get() = get("AI_DOMEN_POOL", "")
    val aiApiKeyPool: String get() = get("AI_API_POOL", "")
    val aiModelPool: String get() = get("AI_MODEL_POOL", "").ifBlank { aiModel }

    // ── Corpus warm-up ──────────────────────────────────────────────────────
    /** Paced far below the scraper rate limit so warming never queues ahead of a real lookup. */
    val warmupWordsPerHour: Int get() = getInt("WARMUP_WORDS_PER_HOUR", 30)
    /** Resume automatically after a restart; the corpus itself records what is already done. */
    val warmupEnabled: Boolean get() = get("WARMUP_ENABLED", "false").equals("true", true)
    /** 0 = the whole list. Set lower to try a slice first. */
    val warmupLimit: Int get() = getInt("WARMUP_LIMIT", 0)

    /**
     * Strongest response constraint to attempt: json_schema | json_object | none.
     * Downgraded automatically if the provider rejects it.
     */
    val aiStructuredMode: n.startapp.services.ai.AiCompat.StructuredMode
        get() = when (get("AI_STRUCTURED_MODE", "json_schema").lowercase()) {
            "none" -> n.startapp.services.ai.AiCompat.StructuredMode.NONE
            "json_object" -> n.startapp.services.ai.AiCompat.StructuredMode.JSON_OBJECT
            else -> n.startapp.services.ai.AiCompat.StructuredMode.JSON_SCHEMA
        }

    // Google OAuth
    val googleClientId: String get() = get("GOOGLE_CLIENT_ID", "")

    // Admin panel
    val adminSecret: String get() = get("ADMIN_SECRET", "")

    // Resend (transactional email)
    val resendApiKey: String get() = get("RESEND_API_KEY", "")
    val resendFromEmail: String get() = get("RESEND_FROM_EMAIL", "noreply@wordwaverise.com")

    // ── Public site ─────────────────────────────────────────────────────────
    /**
     * Origin the crawlable pages live under. The HTML this backend renders is served to users
     * through the site's nginx, so every canonical, sitemap and internal link must name the site
     * — not `backend.wordwaverise.com`, which would split the same page across two hosts.
     */
    val siteUrl: String get() = get("SITE_URL", "https://wordwaverise.com").trimEnd('/')

    // Account deletion grace period
    val accountDeletionGraceDays: Int get() = getInt("ACCOUNT_DELETION_GRACE_DAYS", 30)
}
