# План дореализации WordWaverise Backend

**Дата создания:** 11 декабря 2025
**Версия:** 1.0
**Статус проекта:** MVP базовые функции реализованы (~30% от ТЗ)

---

## Оценка текущего состояния

### ✅ Реализовано (30%)

#### 1. Базовая инфраструктура
- [x] Ktor сервер на Netty (Application.kt)
- [x] CORS настройка (HTTP.kt) - localhost:5173
- [x] JWT аутентификация (Authentication.kt)
- [x] Логирование (Logging.kt)
- [x] Сериализация JSON (Serialization.kt)
- [x] Обработка исключений (ExceptionHandling.kt)
- [x] PostgreSQL + Exposed ORM + HikariCP
- [x] In-memory кэширование (CacheService.kt с Caffeine)

#### 2. Аутентификация
- [x] POST /api/auth/register
- [x] POST /api/auth/login
- [x] JWT токены (JwtUtil.kt)
- [x] BCrypt хэширование (PasswordUtil.kt)
- [x] Базовая таблица Users (id, email, passwordHash, createdAt)

#### 3. Словарные функции
- [x] GET /api/words/search - поиск слова
- [x] GET /api/words/details - детальная информация с агрегацией
- [x] Интеграция 4 словарных API (FreeDictionary, DictionaryAPI, DataMuse, WordsAPI)
- [x] Кэширование результатов
- [x] DictionaryService с параллельными запросами

#### 4. Сохраненные слова
- [x] POST /api/words/saved - сохранение слова
- [x] GET /api/words/saved - список сохраненных слов
- [x] DELETE /api/words/saved/{word} - удаление
- [x] Таблица SavedWords (упрощенная версия)

#### 5. Флэшкарты
- [x] POST /api/flashcards - создание
- [x] GET /api/flashcards - список всех
- [x] GET /api/flashcards/due - карточки для повторения
- [x] PUT /api/flashcards/{id} - обновление
- [x] POST /api/flashcards/review - повторение с обновлением SM-2
- [x] DELETE /api/flashcards/{id} - удаление
- [x] GET /api/flashcards/statistics - статистика
- [x] SM-2 алгоритм spaced repetition (SpacedRepetitionAlgorithm.kt)
- [x] Таблица Flashcards (полная версия)

---

## 🚧 Требуется реализовать (70%)

### PHASE 1: Система подписок и лимитов (Приоритет: КРИТИЧЕСКИЙ)
**Время: 1-2 недели | Сложность: Средняя**

#### 1.1 База данных

**Создать миграции Flyway:**
```
src/main/resources/db/migration/
├── V1__initial_schema.sql (users, saved_words)
├── V2__add_flashcards.sql (flashcards)
├── V3__add_subscriptions.sql (subscriptions) ⭐ NEW
├── V4__add_usage_limits.sql (usage_limits) ⭐ NEW
└── V5__add_user_stats.sql (user_stats) ⭐ NEW
```

**Таблицы:**
- **subscriptions** (tier: free/student/premium, status, validFrom, validUntil, activationCode)
- **usage_limits** (userId, feature, count, resetAt)
- **user_stats** (wordsSaved, wordsMastered, cardsReviewed, currentStreak, longestStreak)

**Расширить таблицу Users:**
```sql
ALTER TABLE users ADD COLUMN last_login TIMESTAMP;
ALTER TABLE users ADD COLUMN is_active BOOLEAN DEFAULT TRUE;
ALTER TABLE users ADD COLUMN email_verified BOOLEAN DEFAULT FALSE;
```

**Расширить таблицу SavedWords:**
```sql
ALTER TABLE saved_words ADD COLUMN notes TEXT;
ALTER TABLE saved_words ADD COLUMN last_reviewed TIMESTAMP;
ALTER TABLE saved_words ADD COLUMN mastery_level INTEGER DEFAULT 0;
```

#### 1.2 Модели данных

**Создать файлы:**
- `models/database/Subscriptions.kt` - Subscriptions table + Subscription data class
- `models/database/UsageLimits.kt` - UsageLimits table + UsageLimit data class
- `models/database/UserStats.kt` - UserStats table + UserStat data class
- `models/api/SubscriptionModels.kt` - DTO для API (ActivateCodeRequest, SubscriptionStatusResponse)

**Enums:**
```kotlin
enum class SubscriptionTier { FREE, STUDENT, PREMIUM }
enum class SubscriptionStatus { ACTIVE, EXPIRED, CANCELLED }
```

#### 1.3 Репозитории

**Создать:**
- `repositories/SubscriptionRepository.kt`
  - createSubscription(userId, tier, validUntil?, activationCode?)
  - getActiveSubscription(userId)
  - activateCode(userId, code)
  - expireSubscriptions() // периодическая задача

- `repositories/UsageLimitRepository.kt`
  - incrementUsage(userId, feature)
  - checkLimit(userId, feature, maxLimit)
  - resetExpiredLimits() // периодическая задача

- `repositories/UserStatsRepository.kt`
  - getStats(userId)
  - incrementWordsSaved(userId)
  - incrementCardsReviewed(userId, correct)
  - updateStreak(userId)

#### 1.4 Сервисы

**Создать:**
- `services/SubscriptionService.kt`
  - getUserSubscription(userId): Subscription
  - activateStudentCode(userId, code)
  - checkFeatureAccess(userId, feature): Boolean
  - getLimitsForTier(tier): Map<String, Int>

**Лимиты по тарифам:**
```kotlin
FREE:
  - maxWords: 100
  - aiExamples: 0
  - pronunciationChecks: 0
  - sentenceChecks: 0

STUDENT:
  - maxWords: 5000
  - aiExamples: 100/day
  - pronunciationChecks: 50/day
  - sentenceChecks: 30/day

PREMIUM:
  - maxWords: unlimited
  - aiExamples: unlimited
  - pronunciationChecks: unlimited
  - sentenceChecks: unlimited
```

- `services/UserStatsService.kt`
  - updateStats(userId, event: StatsEvent)
  - calculateStreak(userId)

#### 1.5 Middleware

**Создать:**
- `middleware/SubscriptionMiddleware.kt`
  - Проверка подписки перед доступом к premium эндпоинтам
  - Проверка лимитов использования
  - Возврат 402 Payment Required / 429 Too Many Requests

#### 1.6 Routes

**Создать `routes/SubscriptionRoutes.kt`:**
- **GET /api/subscription/status** - статус подписки, лимиты, использование
  ```json
  {
    "tier": "student",
    "status": "active",
    "validUntil": "2025-12-31T23:59:59Z",
    "limits": {
      "aiExamples": { "used": 45, "max": 100, "resetAt": "2025-12-12T00:00:00Z" },
      "wordsSaved": { "used": 234, "max": 5000 }
    }
  }
  ```

- **POST /api/subscription/activate-code** - активация промокода
  ```json
  Request: { "activationCode": "STUDENT2025-XXXX" }
  Response: { "tier": "student", "validUntil": "2026-06-30T23:59:59Z" }
  ```

**Обновить `routes/AuthRoutes.kt`:**
- **GET /api/auth/me** - добавить subscription и stats в ответ
  ```json
  {
    "user": { "id": 1, "email": "user@example.com" },
    "subscription": { "tier": "student", "status": "active" },
    "stats": { "wordsSaved": 234, "cardsReviewed": 456 }
  }
  ```

#### 1.7 Автоматизация

**Создать периодические задачи:**
- Проверка истечения подписок (каждый час)
- Сброс дневных лимитов (каждую полночь UTC)
- Обновление статистики стриков (каждый день)

**Использовать:** Kotlin Coroutines + `kotlinx.coroutines.delay` или Quartz Scheduler

#### 1.8 Обновить существующие эндпоинты

**SavedWordsRoutes.kt:**
- Добавить проверку лимита maxWords перед сохранением
- Обновлять user_stats при сохранении/удалении слова

**FlashcardRoutes.kt:**
- Обновлять user_stats при повторении карточки
- Обновлять streak при ежедневной активности

**AuthRoutes.kt:**
- Создавать FREE подписку при регистрации
- Создавать начальную статистику при регистрации

---

### PHASE 2: AI интеграция (Gemini + Claude) (Приоритет: ВЫСОКИЙ)
**Время: 2-3 недели | Сложность: Высокая**

#### 2.1 Зависимости

**Добавить в build.gradle.kts:**
```kotlin
// AI Services
implementation("com.google.ai.client.generativeai:generativeai:0.1.2")
implementation("com.anthropic:anthropic-sdk-kotlin:0.1.0")

// HTTP Client для AI APIs (если нет)
implementation("io.ktor:ktor-client-core:$ktor_version")
implementation("io.ktor:ktor-client-cio:$ktor_version")
implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
implementation("io.ktor:ktor-client-logging:$ktor_version")
```

#### 2.2 Конфигурация

**Создать `config/ExternalApisConfig.kt`:**
```kotlin
object ExternalApisConfig {
    val geminiApiKey: String = EnvConfig.get("GEMINI_API_KEY")
    val claudeApiKey: String = EnvConfig.get("CLAUDE_API_KEY")
    val geminiModel: String = "gemini-2.0-flash-exp"
    val claudeModel: String = "claude-haiku-4.5"
}
```

**Переменные окружения (.env):**
```
GEMINI_API_KEY=your_gemini_api_key
CLAUDE_API_KEY=your_claude_api_key
```

#### 2.3 База данных

**Создать миграцию V6__add_ai_prompt_cache.sql:**
```sql
CREATE TABLE ai_prompt_cache (
    id SERIAL PRIMARY KEY,
    cache_key VARCHAR(255) UNIQUE NOT NULL,
    system_prompt TEXT NOT NULL,
    cache_tokens INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    usage_count INTEGER DEFAULT 0
);
```

**Создать `models/database/AIPromptCache.kt`**

#### 2.4 Сервисы

**Создать `services/GeminiService.kt`:**
```kotlin
class GeminiService {
    suspend fun generateExamples(word: String, context: String?): List<String>
    suspend fun checkSentence(word: String, sentence: String): SentenceCheckResult
}
```

**Создать `services/ClaudeService.kt`:**
```kotlin
class ClaudeService {
    suspend fun generateExamples(word: String, context: String?): List<String>
    suspend fun generateBatchExercises(words: List<String>, types: List<ExerciseType>): List<Exercise>
    suspend fun checkSentence(word: String, sentence: String): SentenceCheckResult

    // Prompt caching для экономии токенов
    private fun getCachedSystemPrompt(key: String): String
}
```

**Создать `services/AIService.kt`:**
```kotlin
class AIService(
    private val geminiService: GeminiService,
    private val claudeService: ClaudeService,
    private val subscriptionService: SubscriptionService,
    private val cacheService: CacheService
) {
    suspend fun generateExamples(userId: Int, word: String, context: String?): AIExamplesResponse {
        // 1. Проверить подписку
        // 2. Проверить лимит
        // 3. Проверить кэш
        // 4. Выбрать модель (Gemini для Student, Claude для Premium)
        // 5. Генерировать примеры
        // 6. Кэшировать результат
        // 7. Инкрементировать usage
    }
}
```

#### 2.5 Модели

**Создать `models/api/AIModels.kt`:**
```kotlin
@Serializable
data class GenerateExamplesRequest(
    val word: String,
    val context: String? = null,
    val count: Int = 3
)

@Serializable
data class AIExamplesResponse(
    val word: String,
    val examples: List<AIExample>,
    val model: String
)

@Serializable
data class AIExample(
    val sentence: String,
    val translation: String?,
    val difficulty: String // beginner/intermediate/advanced
)

@Serializable
data class BatchExercisesRequest(
    val words: List<String>,
    val exerciseTypes: List<String> = listOf("fill_blank", "multiple_choice"),
    val count: Int = 3
)

@Serializable
data class BatchExercisesResponse(
    val exercises: List<Exercise>
)

@Serializable
data class Exercise(
    val type: String,
    val word: String,
    val question: String,
    val options: List<String>? = null,
    val correctAnswer: String,
    val explanation: String?
)

@Serializable
data class CheckSentenceRequest(
    val word: String,
    val sentence: String,
    val language: String = "en"
)

@Serializable
data class CheckSentenceResponse(
    val isCorrect: Boolean,
    val confidence: Float, // 0.0 - 1.0
    val feedback: String,
    val correction: String?,
    val alternatives: List<String> = emptyList()
)
```

#### 2.6 Routes

**Создать `routes/AIRoutes.kt`:**
```kotlin
fun Route.aiRoutes(aiService: AIService) {
    route("/api/ai") {
        authenticate("auth-jwt") {
            // Генерация примеров использования слова
            post("/examples") {
                // Только для Student/Premium
                // Rate limit по тарифу
            }

            // Batch генерация упражнений
            post("/exercises/batch") {
                // Только для Student/Premium
                // Максимум 50 слов
            }

            // Проверка использования слова в предложении
            post("/check-sentence") {
                // Только для Student/Premium
                // Rate limit
            }
        }
    }
}
```

**Зарегистрировать в `Routing.kt`:**
```kotlin
fun Application.configureRouting() {
    routing {
        // ... existing routes
        aiRoutes(aiService)
    }
}
```

#### 2.7 Кэширование

**Обновить `services/CacheService.kt`:**
```kotlin
// AI кэш с длительным TTL
suspend fun cacheAIExamples(word: String, context: String?, model: String, examples: List<AIExample>)
suspend fun getAICachedExamples(word: String, context: String?, model: String): List<AIExample>?

// Кэш упражнений
suspend fun cacheExercises(hash: String, exercises: List<Exercise>)
suspend fun getCachedExercises(hash: String): List<Exercise>?
```

#### 2.8 Rate Limiting

**Создать `services/RateLimitService.kt`:**
```kotlin
class RateLimitService(
    private val usageLimitRepository: UsageLimitRepository,
    private val subscriptionService: SubscriptionService
) {
    suspend fun checkAndIncrement(userId: Int, feature: String): RateLimitResult {
        val subscription = subscriptionService.getUserSubscription(userId)
        val limits = subscriptionService.getLimitsForTier(subscription.tier)
        val maxLimit = limits[feature] ?: return RateLimitResult.Forbidden

        if (maxLimit == -1) { // unlimited
            usageLimitRepository.incrementUsage(userId, feature)
            return RateLimitResult.Allowed
        }

        val canUse = usageLimitRepository.checkLimit(userId, feature, maxLimit)
        if (canUse) {
            usageLimitRepository.incrementUsage(userId, feature)
            return RateLimitResult.Allowed
        }

        return RateLimitResult.LimitExceeded(maxLimit)
    }
}

sealed class RateLimitResult {
    object Allowed : RateLimitResult()
    object Forbidden : RateLimitResult() // Функция недоступна для тарифа
    data class LimitExceeded(val limit: Int) : RateLimitResult()
}
```

#### 2.9 Error Handling

**Обновить `ExceptionHandling.kt`:**
```kotlin
status(HttpStatusCode.PaymentRequired) { call, cause ->
    // 402 - Требуется подписка
}

status(HttpStatusCode.TooManyRequests) { call, cause ->
    // 429 - Превышен лимит
    call.respond(HttpStatusCode.TooManyRequests, ApiResponse.error<Unit>(
        "Rate limit exceeded. Upgrade your subscription for more requests."
    ))
}
```

---

### PHASE 3: Админская панель (Приоритет: СРЕДНИЙ)
**Время: 2-3 недели | Сложность: Средняя**

#### 3.1 База данных

**Создать миграции:**
- **V7__add_admin_users.sql** (admin_users)
- **V8__add_feature_flags.sql** (feature_flags)
- **V9__add_admin_logs_and_notes.sql** (admin_actions_log, user_notes)
- **V10__add_user_ban_fields.sql** (расширение users)

**Таблицы:**
```sql
-- Admin users
CREATE TABLE admin_users (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id),
    role VARCHAR(50) NOT NULL CHECK (role IN ('super_admin', 'moderator', 'support')),
    permissions JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by INTEGER REFERENCES admin_users(id)
);

-- Feature flags
CREATE TABLE feature_flags (
    id SERIAL PRIMARY KEY,
    feature_key VARCHAR(100) UNIQUE NOT NULL,
    feature_name VARCHAR(255) NOT NULL,
    description TEXT,
    tier VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    daily_limit INTEGER,
    config JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by INTEGER REFERENCES admin_users(id)
);

-- Admin actions log
CREATE TABLE admin_actions_log (
    id SERIAL PRIMARY KEY,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    action_type VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id INTEGER,
    changes JSONB NOT NULL,
    reason TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- User notes
CREATE TABLE user_notes (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    note_type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Расширение users для бана
ALTER TABLE users ADD COLUMN is_banned BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN ban_reason TEXT;
ALTER TABLE users ADD COLUMN banned_at TIMESTAMP;
ALTER TABLE users ADD COLUMN banned_by INTEGER REFERENCES admin_users(id);
ALTER TABLE users ADD COLUMN ban_expires_at TIMESTAMP;
```

#### 3.2 Модели

**Создать:**
- `models/database/AdminUsers.kt`
- `models/database/FeatureFlags.kt`
- `models/database/AdminActionsLog.kt`
- `models/database/UserNotes.kt`
- `models/api/AdminModels.kt` (AdminLoginRequest, AdminUserResponse, BanUserRequest, etc.)

**Enums:**
```kotlin
enum class AdminRole {
    SUPER_ADMIN,  // Полный доступ
    MODERATOR,    // Управление пользователями, баны
    SUPPORT       // Просмотр данных, заметки
}

enum class NoteType {
    WARNING, BAN, INFO, SUPPORT
}
```

#### 3.3 Репозитории

**Создать:**
- `repositories/AdminRepository.kt`
- `repositories/FeatureFlagsRepository.kt`
- `repositories/AdminActionsLogRepository.kt`
- `repositories/UserNotesRepository.kt`

#### 3.4 Сервисы

**Создать `services/AdminService.kt`:**
```kotlin
class AdminService {
    suspend fun authenticateAdmin(email: String, password: String): AdminUser?
    suspend fun checkPermission(adminId: Int, permission: String): Boolean
    suspend fun logAction(adminId: Int, actionType: String, targetType: String, targetId: Int, changes: Map<String, Any>, reason: String?)

    // User management
    suspend fun getUsers(filters: UserFilters, page: Int, limit: Int): PaginatedUsers
    suspend fun getUserDetails(userId: Int): UserDetailsResponse
    suspend fun banUser(adminId: Int, userId: Int, reason: String, duration: Duration?, ip: String)
    suspend fun unbanUser(adminId: Int, userId: Int)
    suspend fun updateUserSubscription(adminId: Int, userId: Int, tier: SubscriptionTier, validUntil: Instant?)
    suspend fun addUserNote(adminId: Int, userId: Int, noteType: NoteType, content: String)

    // Statistics
    suspend fun getSystemStats(): SystemStats
}
```

**Создать `services/FeatureService.kt`:**
```kotlin
class FeatureService {
    suspend fun isFeatureEnabled(featureKey: String, tier: SubscriptionTier): Boolean
    suspend fun getFeatureConfig(featureKey: String, tier: SubscriptionTier): Map<String, Any>
    suspend fun updateFeature(adminId: Int, featureId: Int, updates: FeatureUpdates)
}
```

#### 3.5 Middleware

**Создать `middleware/AdminMiddleware.kt`:**
```kotlin
fun Application.configureAdminAuth() {
    install(Authentication) {
        jwt("admin-jwt") {
            verifier(JWTVerifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asInt()
                val adminUser = adminRepository.findByUserId(userId)
                if (adminUser != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}

// Permission-based authorization
suspend fun checkPermission(call: ApplicationCall, permission: String) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    val adminUser = adminRepository.findByUserId(userId!!)

    if (!adminUser.hasPermission(permission)) {
        throw ForbiddenException("Insufficient permissions")
    }
}
```

#### 3.6 Routes

**Создать `routes/admin/AdminAuthRoutes.kt`:**
```kotlin
fun Route.adminAuthRoutes() {
    route("/api/admin/auth") {
        post("/login") { /* Admin login */ }
    }
}
```

**Создать `routes/admin/AdminUserRoutes.kt`:**
```kotlin
fun Route.adminUserRoutes(adminService: AdminService) {
    route("/api/admin/users") {
        authenticate("admin-jwt") {
            get {
                checkPermission(call, "users.view")
                // GET /api/admin/users?search=&tier=&banned=&page=1&limit=20
            }

            get("/{userId}") {
                checkPermission(call, "users.view")
                // GET /api/admin/users/123
            }

            put("/{userId}/subscription") {
                checkPermission(call, "subscriptions.edit")
                // PUT /api/admin/users/123/subscription
            }

            post("/{userId}/ban") {
                checkPermission(call, "users.ban")
                // POST /api/admin/users/123/ban
            }

            delete("/{userId}/ban") {
                checkPermission(call, "users.ban")
                // DELETE /api/admin/users/123/ban (unban)
            }

            post("/{userId}/notes") {
                checkPermission(call, "users.edit")
                // POST /api/admin/users/123/notes
            }
        }
    }
}
```

**Создать `routes/admin/AdminFeatureRoutes.kt`:**
```kotlin
fun Route.adminFeatureRoutes(featureService: FeatureService) {
    route("/api/admin/features") {
        authenticate("admin-jwt") {
            get { /* GET /api/admin/features */ }
            put("/{featureId}") { /* PUT /api/admin/features/1 */ }
            post { /* POST /api/admin/features */ }
        }
    }
}
```

**Создать `routes/admin/AdminLogsRoutes.kt`:**
```kotlin
fun Route.adminLogsRoutes() {
    route("/api/admin/logs") {
        authenticate("admin-jwt") {
            get {
                checkPermission(call, "logs.view")
                // GET /api/admin/logs?adminId=&actionType=&dateFrom=&dateTo=
            }
        }
    }
}
```

**Создать `routes/admin/AdminStatsRoutes.kt`:**
```kotlin
fun Route.adminStatsRoutes(adminService: AdminService) {
    route("/api/admin/stats") {
        authenticate("admin-jwt") {
            get {
                checkPermission(call, "stats.view")
                // Статистика системы
            }
        }
    }
}
```

#### 3.7 Seed Data

**Создать первого супер-админа (скрипт или migration):**
```sql
-- Создать пользователя-админа
INSERT INTO users (email, password_hash, is_active, email_verified)
VALUES ('admin@wordwaverise.com', '$2a$...', TRUE, TRUE);

-- Сделать его супер-админом
INSERT INTO admin_users (user_id, role, permissions)
VALUES (
    (SELECT id FROM users WHERE email = 'admin@wordwaverise.com'),
    'super_admin',
    '["users.view", "users.edit", "users.ban", "subscriptions.view", "subscriptions.edit", "features.view", "features.edit", "logs.view", "admin.manage"]'::jsonb
);
```

#### 3.8 Логирование админских действий

**Каждое действие админа должно логироваться:**
```kotlin
// Пример: бан пользователя
adminService.logAction(
    adminId = adminId,
    actionType = "user.ban",
    targetType = "user",
    targetId = userId,
    changes = mapOf(
        "isBanned" to true,
        "banReason" to reason,
        "banExpiresAt" to expiresAt
    ),
    reason = reason
)
```

---

### PHASE 4: Redis & улучшенное кэширование (Приоритет: СРЕДНИЙ)
**Время: 1 неделя | Сложность: Средняя**

#### 4.1 Зависимости

**Добавить в build.gradle.kts:**
```kotlin
// Redis
implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.7.3")
```

#### 4.2 Конфигурация

**Создать `config/RedisConfig.kt`:**
```kotlin
object RedisConfig {
    val host: String = EnvConfig.get("REDIS_HOST", "localhost")
    val port: Int = EnvConfig.get("REDIS_PORT", "6379").toInt()
    val password: String? = EnvConfig.getOrNull("REDIS_PASSWORD")
    val db: Int = EnvConfig.get("REDIS_DB", "0").toInt()

    fun createClient(): RedisClient {
        val uri = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .withDatabase(db)
            .apply { password?.let { withPassword(it.toCharArray()) } }
            .build()
        return RedisClient.create(uri)
    }
}
```

#### 4.3 Многоуровневое кэширование

**Обновить `services/CacheService.kt`:**
```kotlin
class CacheService {
    // L1: In-memory cache (Caffeine) - быстрый, но локальный
    private val l1Cache: Cache<String, String> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(5.minutes)
        .build()

    // L2: Redis cache - распределенный, персистентный
    private val redisClient: StatefulRedisConnection<String, String> =
        RedisConfig.createClient().connect()

    suspend fun get(key: String): String? {
        // 1. Проверить L1
        l1Cache.getIfPresent(key)?.let { return it }

        // 2. Проверить L2 (Redis)
        val value = redisClient.async().get(key).await()

        // 3. Положить в L1 если нашли в L2
        if (value != null) {
            l1Cache.put(key, value)
        }

        return value
    }

    suspend fun set(key: String, value: String, ttl: Duration) {
        // Сохранить в L1
        l1Cache.put(key, value)

        // Сохранить в L2 (Redis)
        redisClient.async().setex(key, ttl.inWholeSeconds, value).await()
    }

    suspend fun delete(key: String) {
        l1Cache.invalidate(key)
        redisClient.async().del(key).await()
    }

    suspend fun clear() {
        l1Cache.invalidateAll()
        redisClient.async().flushdb().await()
    }
}
```

#### 4.4 Кэширование по типам данных

**Ключи кэша:**
```kotlin
object CacheKeys {
    fun word(word: String) = "word:${word.lowercase()}"
    fun aiExamples(word: String, context: String?, model: String) =
        "ai:examples:$word:${context?.hashCode() ?: "null"}:$model"
    fun aiExercises(wordsHash: String) = "ai:exercises:$wordsHash"
    fun aiSentenceCheck(sentenceHash: String) = "ai:sentence:$sentenceHash"
    fun userSubscription(userId: Int) = "user:$userId:subscription"
    fun userStats(userId: Int) = "user:$userId:stats"
}
```

**TTL по типам:**
```kotlin
object CacheTTL {
    val WORD_DEFINITION = 24.hours
    val AI_EXAMPLES = 7.days
    val AI_EXERCISES = 30.days
    val AI_SENTENCE_CHECK = 7.days
    val USER_SUBSCRIPTION = 1.hours
    val USER_STATS = 5.minutes
}
```

#### 4.5 Docker Compose

**Обновить/создать `docker-compose.yml`:**
```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - DATABASE_URL=jdbc:postgresql://postgres:5432/wordwaverise
      - REDIS_HOST=redis
      - REDIS_PORT=6379
    depends_on:
      - postgres
      - redis

  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: wordwaverise
      POSTGRES_USER: wordwaverise
      POSTGRES_PASSWORD: your_password
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes

volumes:
  postgres_data:
  redis_data:
```

---

### PHASE 5: Production Ready (Приоритет: НИЗКИЙ)
**Время: 2-3 недели | Сложность: Средняя**

#### 5.1 Database Migrations Setup

**Добавить Flyway:**
```kotlin
// build.gradle.kts
implementation("org.flywaydb:flyway-core:10.4.1")
implementation("org.flywaydb:flyway-database-postgresql:10.4.1")
```

**Настроить в Application.kt:**
```kotlin
fun Application.configureDatabases() {
    val flyway = Flyway.configure()
        .dataSource(DatabaseConfig.url, DatabaseConfig.user, DatabaseConfig.password)
        .locations("classpath:db/migration")
        .load()

    flyway.migrate()

    DatabaseFactory.init()
}
```

#### 5.2 Testing Suite

**Структура тестов:**
```
src/test/kotlin/n/startapp/
├── routes/
│   ├── AuthRoutesTest.kt
│   ├── WordRoutesTest.kt
│   ├── FlashcardRoutesTest.kt
│   ├── SubscriptionRoutesTest.kt
│   └── AIRoutesTest.kt
├── services/
│   ├── DictionaryServiceTest.kt
│   ├── SubscriptionServiceTest.kt
│   ├── AIServiceTest.kt
│   └── FlashcardServiceTest.kt
├── repositories/
│   └── UserRepositoryTest.kt
└── utils/
    └── SpacedRepetitionAlgorithmTest.kt
```

**Зависимости:**
```kotlin
testImplementation("io.ktor:ktor-server-tests-jvm")
testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("org.testcontainers:postgresql:1.19.3")
```

**Пример теста:**
```kotlin
class AuthRoutesTest {
    @Test
    fun `test register creates user`() = testApplication {
        application { module() }

        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email": "test@example.com", "password": "password123"}""")
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val response = body<ApiResponse<UserResponse>>()
            assertEquals("ok", response.status)
        }
    }
}
```

#### 5.3 Logging Improvements

**Структурированное логирование:**
```kotlin
// Создать utils/Logger.kt
object AppLogger {
    private val logger = LoggerFactory.getLogger("WordWaveriseApp")

    fun logRequest(method: String, path: String, userId: Int?, duration: Long) {
        logger.info("REQUEST: method=$method path=$path userId=$userId duration=${duration}ms")
    }

    fun logError(error: Throwable, context: Map<String, Any> = emptyMap()) {
        logger.error("ERROR: message=${error.message} context=$context", error)
    }

    fun logAdminAction(adminId: Int, action: String, target: String) {
        logger.info("ADMIN_ACTION: adminId=$adminId action=$action target=$target")
    }

    fun logAIUsage(userId: Int, model: String, feature: String, tokens: Int) {
        logger.info("AI_USAGE: userId=$userId model=$model feature=$feature tokens=$tokens")
    }
}
```

#### 5.4 Monitoring Endpoints

**Создать `routes/MonitoringRoutes.kt`:**
```kotlin
fun Route.monitoringRoutes() {
    route("/api") {
        get("/health") {
            val dbHealthy = checkDatabaseConnection()
            val redisHealthy = checkRedisConnection()

            val status = if (dbHealthy && redisHealthy) "healthy" else "degraded"

            call.respond(mapOf(
                "status" to status,
                "version" to "1.0.0",
                "timestamp" to Clock.System.now(),
                "services" to mapOf(
                    "database" to if (dbHealthy) "up" else "down",
                    "redis" to if (redisHealthy) "up" else "down"
                )
            ))
        }

        get("/metrics") {
            // Метрики для Prometheus (опционально)
            call.respondText(
                contentType = ContentType.Text.Plain,
                text = generatePrometheusMetrics()
            )
        }
    }
}
```

#### 5.5 Environment Configuration

**Создать файлы конфигурации:**
```
src/main/resources/
├── application.yaml (development)
├── application-production.yaml
└── application-staging.yaml
```

**application-production.yaml:**
```yaml
ktor:
  deployment:
    port: 8080
    watch: []
  application:
    modules:
      - n.startapp.ApplicationKt.module

database:
  url: ${DATABASE_URL}
  driver: org.postgresql.Driver
  maxPoolSize: 20

redis:
  host: ${REDIS_HOST}
  port: ${REDIS_PORT}

logging:
  level: INFO
```

#### 5.6 Security Enhancements

**Email Verification:**
- Создать таблицу email_verification_tokens
- Отправка email при регистрации
- Эндпоинт POST /api/auth/verify-email

**Password Reset:**
- Создать таблицу password_reset_tokens
- POST /api/auth/forgot-password
- POST /api/auth/reset-password

**Rate Limiting на уровне IP:**
```kotlin
install(RateLimit) {
    register(RateLimitName("api")) {
        rateLimiter(limit = 100, refillPeriod = 1.minutes)
    }
}
```

#### 5.7 Deployment

**Dockerfile:**
```dockerfile
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle buildFatJar --no-daemon

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**CI/CD (GitHub Actions):**
```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run tests
        run: ./gradlew test

  deploy:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to VPS
        # ... SSH deployment steps
```

**Nginx Configuration:**
```nginx
server {
    listen 80;
    server_name api.wordwaverise.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## Приоритеты и Timeline

### Критический путь (обязательно для MVP+):
1. **PHASE 1** - Система подписок (2 недели)
2. **PHASE 2** - AI интеграция (3 недели)

**Итого MVP+: 5 недель**

### Рекомендуемые дополнения:
3. **PHASE 3** - Админская панель (3 недели)
4. **PHASE 4** - Redis кэширование (1 неделя)

**Итого полнофункциональный продукт: 9 недель**

### Production Ready:
5. **PHASE 5** - Production готовность (3 недели)

**Итого Production Ready: 12 недель (~3 месяца)**

---

## Метрики прогресса

**Текущий статус: 30% (15/50 основных функций)**

### Реализовано: 15
- Базовая инфраструктура: 8/8
- Аутентификация: 2/4
- Словарные функции: 3/3
- Сохраненные слова: 2/3
- Флэшкарты: 0/8 (частично)

### Требуется: 35
- Подписки: 0/7
- AI функции: 0/5
- Админка: 0/12
- Redis: 0/3
- Production: 0/8

---

## Риски и зависимости

### Критические зависимости:
1. **API ключи** - нужны ключи для Gemini и Claude
2. **Redis** - требуется для production
3. **PostgreSQL** - текущая БД требует миграций

### Технические риски:
1. **AI costs** - Gemini и Claude могут быть дорогими при масштабировании
   - Mitigation: агрессивное кэширование, rate limiting
2. **Database schema changes** - существующие данные нужно мигрировать
   - Mitigation: аккуратные Flyway миграции с rollback
3. **Performance** - много внешних API вызовов
   - Mitigation: параллельные запросы, кэширование, Redis

---

## Рекомендации

### Немедленные действия:
1. Настроить Flyway и создать базовые миграции
2. Реализовать систему подписок (Phase 1)
3. Получить API ключи для Gemini и Claude
4. Настроить Redis локально и в docker-compose

### Best Practices:
1. **Всегда** создавать миграции для изменений БД
2. **Всегда** логировать админские действия
3. **Всегда** проверять лимиты перед AI запросами
4. **Всегда** кэшировать результаты AI
5. **Тестировать** каждый новый эндпоинт

### Оптимизация затрат:
1. Использовать Gemini для Student (дешевле)
2. Использовать Claude только для Premium
3. Максимальное кэширование AI результатов
4. Prompt caching для Claude (экономия до 90% на повторных запросах)

---

## Заключение

Проект находится на хорошей стадии (~30% завершенности). Базовая архитектура качественная, код чистый, следует best practices Kotlin/Ktor.

**Следующий шаг:** Начать с **PHASE 1** (Система подписок), так как это критическая зависимость для всех остальных функций, особенно AI интеграции.

После Phase 1 и Phase 2 проект будет иметь основную ценность для пользователей (словарь + AI функции + подписки).
