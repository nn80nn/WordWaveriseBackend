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

    fun get(key: String, default: String = ""): String {
        return envMap[key] ?: System.getenv(key) ?: default
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
}
