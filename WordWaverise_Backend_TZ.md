# Техническое задание: WordWaverise Backend Server

## 📋 Информация о проекте

**Название проекта:** WordWaverise Backend  
**Платформа:** Server (Ktor + Kotlin)  
**Версия документа:** 1.0  
**Дата создания:** 6 декабря 2024  
**API URL:** https://api.wordwaverise.com

---

## 🎯 Описание проекта

Backend сервер для приложения WordWaverise - централизованный API, который обслуживает Android приложение и веб-версию. Сервер обрабатывает все запросы к внешним словарным API, управляет пользовательскими данными, реализует бизнес-логику и интегрируется с Gemini AI.

### Ключевые принципы:
- **Единая точка входа** - все внешние API доступны только через backend
- **Безопасность** - JWT авторизация, валидация данных, rate limiting
- **Масштабируемость** - горизонтальное масштабирование, кэширование
- **Производительность** - асинхронная обработка, connection pooling
- **Надёжность** - обработка ошибок, логирование, мониторинг

---

## 🛠 Технологический стек

### Core Framework
- **Framework:** Ktor 2.3+ (Kotlin)
- **Language:** Kotlin 1.9+
- **JVM:** OpenJDK 17+
- **Build Tool:** Gradle 8.0+

### База данных
- **Primary DB:** PostgreSQL 15+
- **Cache:** Redis 7+
- **Connection Pool:** HikariCP
- **ORM:** Exposed (Kotlin SQL framework)
- **Migrations:** Flyway

### External Services
- **AI Services:**
  - Google Gemini API (gemini-2.0-flash-exp)
  - Anthropic Claude API (claude-haiku-4.5)
- **Dictionary APIs:**
  - Free Dictionary API (бесплатный)
  - Merriam-Webster API (платный)
  - Oxford Dictionaries API (платный, опционально)
  - WordsAPI (платный, опционально)
- **Словарная база:** Wiktionary XML dump

### Infrastructure
- **Server:** VPS (DigitalOcean/Hetzner)
- **Reverse Proxy:** Nginx
- **SSL:** Let's Encrypt
- **Monitoring:** Prometheus + Grafana (опционально)
- **Logging:** Logback + ELK Stack (опционально)

### Libraries & Dependencies
```kotlin
// Ktor plugins
implementation("io.ktor:ktor-server-core")
implementation("io.ktor:ktor-server-netty")
implementation("io.ktor:ktor-server-content-negotiation")
implementation("io.ktor:ktor-serialization-kotlinx-json")
implementation("io.ktor:ktor-server-cors")
implementation("io.ktor:ktor-server-auth")
implementation("io.ktor:ktor-server-auth-jwt")
implementation("io.ktor:ktor-server-rate-limit")
implementation("io.ktor:ktor-server-call-logging")
implementation("io.ktor:ktor-server-status-pages")

// Database
implementation("org.jetbrains.exposed:exposed-core")
implementation("org.jetbrains.exposed:exposed-dao")
implementation("org.jetbrains.exposed:exposed-jdbc")
implementation("org.jetbrains.exposed:exposed-java-time")
implementation("org.postgresql:postgresql")
implementation("com.zaxxer:HikariCP")
implementation("org.flywaydb:flyway-core")

// Redis
implementation("io.lettuce:lettuce-core")

// HTTP Client
implementation("io.ktor:ktor-client-core")
implementation("io.ktor:ktor-client-cio")
implementation("io.ktor:ktor-client-content-negotiation")
implementation("io.ktor:ktor-client-logging")

// Security
implementation("com.password4j:password4j")
implementation("com.auth0:java-jwt")

// AI Services
implementation("com.google.ai.client.generativeai:generativeai")
implementation("com.anthropic:anthropic-sdk-kotlin:0.1.0")

// Logging
implementation("ch.qos.logback:logback-classic")

// Testing
testImplementation("io.ktor:ktor-server-tests")
testImplementation("org.jetbrains.kotlin:kotlin-test")
testImplementation("io.mockk:mockk")
```

---

## 🏗 Архитектура сервера

### Общая архитектура

```
┌─────────────────────────────────────────────────────────┐
│                     Clients Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │   Android    │  │   Web (Vue)  │  │   Future iOS │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
└─────────┼──────────────────┼──────────────────┼─────────┘
          │                  │                  │
          └──────────────────┼──────────────────┘
                            │ HTTPS
                            ▼
┌─────────────────────────────────────────────────────────┐
│                    Nginx (Reverse Proxy)                 │
│  - SSL Termination                                       │
│  - Load Balancing                                        │
│  - Rate Limiting (basic)                                 │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│                  Ktor Application Server                 │
│  ┌───────────────────────────────────────────────────┐  │
│  │              Routing Layer                        │  │
│  │  /api/auth  /api/words  /api/flashcards  etc.   │  │
│  └────────────────────┬──────────────────────────────┘  │
│  ┌────────────────────┴──────────────────────────────┐  │
│  │           Middleware Layer                        │  │
│  │  - Authentication (JWT)                           │  │
│  │  - Authorization (Roles, Tiers)                   │  │
│  │  - Rate Limiting                                  │  │
│  │  - Request Validation                             │  │
│  │  - Error Handling                                 │  │
│  │  - Logging                                        │  │
│  └────────────────────┬──────────────────────────────┘  │
│  ┌────────────────────┴──────────────────────────────┐  │
│  │            Service Layer                          │  │
│  │  - AuthService                                    │  │
│  │  - DictionaryService                              │  │
│  │  - UserService                                    │  │
│  │  - FlashcardService                               │  │
│  │  - SubscriptionService                            │  │
│  │  - AIService (Gemini)                             │  │
│  └────────────────────┬──────────────────────────────┘  │
│  ┌────────────────────┴──────────────────────────────┐  │
│  │         Repository Layer                          │  │
│  │  - UserRepository                                 │  │
│  │  - WordRepository                                 │  │
│  │  - FlashcardRepository                            │  │
│  │  - SubscriptionRepository                         │  │
│  └────────────────────┬──────────────────────────────┘  │
└─────────────────────┬─┴────────────────┬───────────────┘
                      │                  │
        ┌─────────────┴────────┐    ┌───┴──────────────┐
        │                      │    │                  │
        ▼                      ▼    ▼                  │
┌──────────────┐      ┌──────────────────┐            │
│  PostgreSQL  │      │      Redis       │            │
│   Database   │      │      Cache       │            │
└──────────────┘      └──────────────────┘            │
                                                       │
                                          ┌────────────┴─────────────┐
                                          │   External Services      │
                                          │  - Gemini AI             │
                                          │  - Dictionary APIs       │
                                          │  - Wiktionary Dump       │
                                          └──────────────────────────┘
```

### Модульная структура проекта

```
wordwaverise-server/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/wordwaverise/
│   │   │       ├── Application.kt              # Main entry point
│   │   │       ├── plugins/                    # Ktor plugins configuration
│   │   │       │   ├── Routing.kt
│   │   │       │   ├── Security.kt
│   │   │       │   ├── Serialization.kt
│   │   │       │   ├── Monitoring.kt
│   │   │       │   ├── StatusPages.kt
│   │   │       │   └── RateLimiting.kt
│   │   │       ├── routes/                     # API endpoints
│   │   │       │   ├── AuthRoutes.kt
│   │   │       │   ├── WordRoutes.kt
│   │   │       │   ├── FlashcardRoutes.kt
│   │   │       │   ├── SubscriptionRoutes.kt
│   │   │       │   ├── AIRoutes.kt
│   │   │       │   ├── UserRoutes.kt
│   │   │       │   └── admin/                  # Admin endpoints
│   │   │       │       ├── AdminAuthRoutes.kt
│   │   │       │       ├── AdminUserRoutes.kt
│   │   │       │       ├── AdminFeatureRoutes.kt
│   │   │       │       ├── AdminDictionaryRoutes.kt
│   │   │       │       └── AdminLogsRoutes.kt
│   │   │       ├── services/                   # Business logic
│   │   │       │   ├── AuthService.kt
│   │   │       │   ├── DictionaryService.kt
│   │   │       │   ├── UserService.kt
│   │   │       │   ├── FlashcardService.kt
│   │   │       │   ├── SubscriptionService.kt
│   │   │       │   ├── AIService.kt
│   │   │       │   ├── GeminiService.kt
│   │   │       │   ├── ClaudeService.kt
│   │   │       │   ├── CacheService.kt
│   │   │       │   ├── FeatureService.kt       # Feature flags
│   │   │       │   └── AdminService.kt         # Admin operations
│   │   │       ├── repositories/               # Data access
│   │   │       │   ├── UserRepository.kt
│   │   │       │   ├── WordRepository.kt
│   │   │       │   ├── FlashcardRepository.kt
│   │   │       │   ├── SubscriptionRepository.kt
│   │   │       │   ├── AdminRepository.kt
│   │   │       │   ├── FeatureFlagsRepository.kt
│   │   │       │   ├── AdminActionsLogRepository.kt
│   │   │       │   └── UserNotesRepository.kt
│   │   │       ├── models/                     # Data models
│   │   │       │   ├── database/               # DB entities
│   │   │       │   │   ├── Users.kt
│   │   │       │   │   ├── SavedWords.kt
│   │   │       │   │   ├── Flashcards.kt
│   │   │       │   │   ├── Subscriptions.kt
│   │   │       │   │   ├── UsageLimits.kt
│   │   │       │   │   ├── AdminUsers.kt
│   │   │       │   │   ├── FeatureFlags.kt
│   │   │       │   │   ├── AdminActionsLog.kt
│   │   │       │   │   └── UserNotes.kt
│   │   │       │   ├── api/                    # API request/response
│   │   │       │   │   ├── AuthModels.kt
│   │   │       │   │   ├── WordModels.kt
│   │   │       │   │   ├── FlashcardModels.kt
│   │   │       │   │   ├── SubscriptionModels.kt
│   │   │       │   │   └── AdminModels.kt
│   │   │       │   └── external/               # External API models
│   │   │       │       ├── DictionaryApiModels.kt
│   │   │       │       ├── GeminiModels.kt
│   │   │       │       └── ClaudeModels.kt
│   │   │       ├── utils/                      # Utilities
│   │   │       │   ├── JWTConfig.kt
│   │   │       │   ├── PasswordUtils.kt
│   │   │       │   ├── ValidationUtils.kt
│   │   │       │   ├── DateTimeUtils.kt
│   │   │       │   └── Extensions.kt
│   │   │       ├── config/                     # Configuration
│   │   │       │   ├── DatabaseConfig.kt
│   │   │       │   ├── RedisConfig.kt
│   │   │       │   ├── ExternalApisConfig.kt
│   │   │       │   └── AppConfig.kt
│   │   │       └── middleware/                 # Custom middleware
│   │   │           ├── AuthMiddleware.kt
│   │   │           ├── AdminMiddleware.kt      # Admin auth
│   │   │           ├── RateLimitMiddleware.kt
│   │   │           └── ValidationMiddleware.kt
│   │   └── resources/
│   │       ├── application.conf                # Main config
│   │       ├── logback.xml                     # Logging config
│   │       └── db/
│   │           └── migration/                  # Flyway migrations
│   │               ├── V1__initial_schema.sql
│   │               ├── V2__add_flashcards.sql
│   │               ├── V3__add_subscriptions.sql
│   │               ├── V4__add_usage_limits.sql
│   │               ├── V5__add_user_stats.sql
│   │               ├── V6__add_ai_prompt_cache.sql
│   │               ├── V7__add_admin_users.sql
│   │               ├── V8__add_feature_flags.sql
│   │               ├── V9__add_admin_logs_and_notes.sql
│   │               └── V10__add_user_ban_fields.sql
│   └── test/
│       └── kotlin/
│           └── com/wordwaverise/
│               ├── routes/                     # Route tests
│               ├── services/                   # Service tests
│               └── repositories/               # Repository tests
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── Dockerfile
├── docker-compose.yml
└── README.md
```

---

## 🗄 База данных (PostgreSQL)

### Схема базы данных

#### Таблица: users
```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    email_verified BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_created_at ON users(created_at);
```

**Kotlin Entity:**
```kotlin
object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
    val lastLogin = timestamp("last_login").nullable()
    val isActive = bool("is_active").default(true)
    val emailVerified = bool("email_verified").default(false)
    
    override val primaryKey = PrimaryKey(id)
}

data class User(
    val id: Int,
    val email: String,
    val passwordHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLogin: Instant?,
    val isActive: Boolean,
    val emailVerified: Boolean
)
```

#### Таблица: subscriptions
```sql
CREATE TABLE subscriptions (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier VARCHAR(50) NOT NULL CHECK (tier IN ('free', 'student', 'premium')),
    status VARCHAR(50) NOT NULL CHECK (status IN ('active', 'expired', 'cancelled')),
    valid_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMP,
    activation_code VARCHAR(100),
    payment_method VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscriptions_user_id ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_status ON subscriptions(status);
CREATE UNIQUE INDEX idx_subscriptions_activation_code ON subscriptions(activation_code) WHERE activation_code IS NOT NULL;
```

**Kotlin Entity:**
```kotlin
object Subscriptions : Table("subscriptions") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val tier = varchar("tier", 50)
    val status = varchar("status", 50)
    val validFrom = timestamp("valid_from").defaultExpression(CurrentTimestamp())
    val validUntil = timestamp("valid_until").nullable()
    val activationCode = varchar("activation_code", 100).nullable().uniqueIndex()
    val paymentMethod = varchar("payment_method", 50).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
    
    override val primaryKey = PrimaryKey(id)
}

enum class SubscriptionTier {
    FREE, STUDENT, PREMIUM
}

enum class SubscriptionStatus {
    ACTIVE, EXPIRED, CANCELLED
}

data class Subscription(
    val id: Int,
    val userId: Int,
    val tier: SubscriptionTier,
    val status: SubscriptionStatus,
    val validFrom: Instant,
    val validUntil: Instant?,
    val activationCode: String?,
    val paymentMethod: String?
)
```

#### Таблица: saved_words
```sql
CREATE TABLE saved_words (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    word VARCHAR(255) NOT NULL,
    translation VARCHAR(500),
    notes TEXT,
    saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_reviewed TIMESTAMP,
    mastery_level INTEGER DEFAULT 0 CHECK (mastery_level >= 0 AND mastery_level <= 5),
    UNIQUE(user_id, word)
);

CREATE INDEX idx_saved_words_user_id ON saved_words(user_id);
CREATE INDEX idx_saved_words_word ON saved_words(word);
CREATE INDEX idx_saved_words_saved_at ON saved_words(saved_at);
```

**Kotlin Entity:**
```kotlin
object SavedWords : Table("saved_words") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val word = varchar("word", 255)
    val translation = varchar("translation", 500).nullable()
    val notes = text("notes").nullable()
    val savedAt = timestamp("saved_at").defaultExpression(CurrentTimestamp())
    val lastReviewed = timestamp("last_reviewed").nullable()
    val masteryLevel = integer("mastery_level").default(0)
    
    override val primaryKey = PrimaryKey(id)
    
    init {
        uniqueIndex(userId, word)
    }
}

data class SavedWord(
    val id: Int,
    val userId: Int,
    val word: String,
    val translation: String?,
    val notes: String?,
    val savedAt: Instant,
    val lastReviewed: Instant?,
    val masteryLevel: Int
)
```

#### Таблица: flashcards
```sql
CREATE TABLE flashcards (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    word VARCHAR(255) NOT NULL,
    translation VARCHAR(500),
    definition TEXT,
    example TEXT,
    next_review TIMESTAMP NOT NULL,
    interval INTEGER NOT NULL DEFAULT 1, -- days
    ease_factor FLOAT NOT NULL DEFAULT 2.5,
    repetitions INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, word)
);

CREATE INDEX idx_flashcards_user_id ON flashcards(user_id);
CREATE INDEX idx_flashcards_next_review ON flashcards(next_review);
CREATE INDEX idx_flashcards_user_next_review ON flashcards(user_id, next_review);
```

**Kotlin Entity:**
```kotlin
object Flashcards : Table("flashcards") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val word = varchar("word", 255)
    val translation = varchar("translation", 500).nullable()
    val definition = text("definition").nullable()
    val example = text("example").nullable()
    val nextReview = timestamp("next_review")
    val interval = integer("interval").default(1)
    val easeFactor = float("ease_factor").default(2.5f)
    val repetitions = integer("repetitions").default(0)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
    
    override val primaryKey = PrimaryKey(id)
    
    init {
        uniqueIndex(userId, word)
    }
}

data class Flashcard(
    val id: Int,
    val userId: Int,
    val word: String,
    val translation: String?,
    val definition: String?,
    val example: String?,
    val nextReview: Instant,
    val interval: Int,
    val easeFactor: Float,
    val repetitions: Int
)
```

#### Таблица: usage_limits
```sql
CREATE TABLE usage_limits (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature VARCHAR(100) NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    reset_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, feature, reset_at)
);

CREATE INDEX idx_usage_limits_user_feature ON usage_limits(user_id, feature);
CREATE INDEX idx_usage_limits_reset_at ON usage_limits(reset_at);
```

**Features:**
- `ai_examples` - AI примеры
- `pronunciation_checks` - Проверка произношения
- `sentence_checks` - Проверка предложений
- `premium_dictionary` - Премиум словари

**Kotlin Entity:**
```kotlin
object UsageLimits : Table("usage_limits") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val feature = varchar("feature", 100)
    val count = integer("count").default(0)
    val resetAt = timestamp("reset_at")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
    
    override val primaryKey = PrimaryKey(id)
    
    init {
        uniqueIndex(userId, feature, resetAt)
    }
}

data class UsageLimit(
    val id: Int,
    val userId: Int,
    val feature: String,
    val count: Int,
    val resetAt: Instant
)
```

#### Таблица: user_stats
```sql
CREATE TABLE user_stats (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    words_saved INTEGER DEFAULT 0,
    words_mastered INTEGER DEFAULT 0,
    cards_reviewed INTEGER DEFAULT 0,
    correct_reviews INTEGER DEFAULT 0,
    current_streak INTEGER DEFAULT 0,
    longest_streak INTEGER DEFAULT 0,
    last_activity_date DATE,
    ai_examples_generated INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_stats_user_id ON user_stats(user_id);
```

#### Таблица: ai_prompt_cache
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

CREATE INDEX idx_ai_prompt_cache_key ON ai_prompt_cache(cache_key);
CREATE INDEX idx_ai_prompt_cache_last_used ON ai_prompt_cache(last_used);
```

#### Таблица: admin_users
```sql
CREATE TABLE admin_users (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL CHECK (role IN ('super_admin', 'moderator', 'support')),
    permissions JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by INTEGER REFERENCES admin_users(id)
);

CREATE INDEX idx_admin_users_user_id ON admin_users(user_id);
CREATE INDEX idx_admin_users_role ON admin_users(role);
```

**Permissions structure:**
```json
[
  "users.view",
  "users.edit",
  "users.ban",
  "subscriptions.view",
  "subscriptions.edit",
  "subscriptions.grant",
  "features.view",
  "features.edit",
  "dictionaries.view",
  "dictionaries.edit",
  "logs.view",
  "admin.manage"
]
```

**Kotlin Entity:**
```kotlin
object AdminUsers : Table("admin_users") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").uniqueIndex().references(Users.id, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 50)
    val permissions = jsonb("permissions")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val createdBy = integer("created_by").references(id).nullable()
    
    override val primaryKey = PrimaryKey(id)
}

enum class AdminRole {
    SUPER_ADMIN,  // Полный доступ
    MODERATOR,    // Модерация пользователей
    SUPPORT       // Поддержка пользователей
}

data class AdminUser(
    val id: Int,
    val userId: Int,
    val role: AdminRole,
    val permissions: List<String>,
    val createdAt: Instant,
    val createdBy: Int?
)
```

#### Таблица: feature_flags
```sql
CREATE TABLE feature_flags (
    id SERIAL PRIMARY KEY,
    feature_key VARCHAR(100) UNIQUE NOT NULL,
    feature_name VARCHAR(255) NOT NULL,
    description TEXT,
    tier VARCHAR(50) NOT NULL CHECK (tier IN ('free', 'student', 'premium', 'all')),
    enabled BOOLEAN DEFAULT TRUE,
    daily_limit INTEGER,
    config JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by INTEGER REFERENCES admin_users(id)
);

CREATE INDEX idx_feature_flags_tier ON feature_flags(tier);
CREATE INDEX idx_feature_flags_enabled ON feature_flags(enabled);
CREATE UNIQUE INDEX idx_feature_flags_key ON feature_flags(feature_key);
```

**Примеры записей:**
```sql
-- AI Features
INSERT INTO feature_flags (feature_key, feature_name, description, tier, enabled, daily_limit, config) VALUES
('ai_examples', 'AI Examples Generation', 'Generate example sentences using AI', 'student', true, 100, 
 '{"model": "gemini", "max_examples": 3}'::jsonb),
('ai_examples', 'AI Examples Generation', 'Generate example sentences using AI', 'premium', true, null, 
 '{"model": "claude", "max_examples": 5}'::jsonb),
('ai_exercises_batch', 'Batch Exercise Generation', 'Generate exercises for multiple words', 'student', true, 10, 
 '{"model": "claude", "max_words": 20}'::jsonb),
('ai_exercises_batch', 'Batch Exercise Generation', 'Generate exercises for multiple words', 'premium', true, null, 
 '{"model": "claude", "max_words": 50}'::jsonb),
('ai_sentence_check', 'Sentence Checking', 'Check if word is used correctly', 'student', true, 30, 
 '{"model": "gemini"}'::jsonb),
('ai_sentence_check', 'Sentence Checking', 'Check if word is used correctly', 'premium', true, null, 
 '{"model": "claude"}'::jsonb),
('pronunciation_check', 'Pronunciation Checking', 'AI-powered pronunciation verification', 'student', false, 50, 
 '{"model": "whisper"}'::jsonb);

-- Dictionary APIs
INSERT INTO feature_flags (feature_key, feature_name, description, tier, enabled, daily_limit, config) VALUES
('dict_free', 'Free Dictionary API', 'Basic dictionary lookups', 'all', true, null, 
 '{"api": "free_dictionary"}'::jsonb),
('dict_merriam_webster', 'Merriam-Webster API', 'Premium dictionary with examples', 'student', true, 200, 
 '{"api": "merriam_webster", "paid": true}'::jsonb),
('dict_merriam_webster', 'Merriam-Webster API', 'Premium dictionary with examples', 'premium', true, null, 
 '{"api": "merriam_webster", "paid": true}'::jsonb),
('dict_oxford', 'Oxford Dictionaries API', 'Comprehensive British/American English', 'premium', false, null, 
 '{"api": "oxford", "paid": true}'::jsonb);
```

**Kotlin Entity:**
```kotlin
object FeatureFlags : Table("feature_flags") {
    val id = integer("id").autoIncrement()
    val featureKey = varchar("feature_key", 100).uniqueIndex()
    val featureName = varchar("feature_name", 255)
    val description = text("description").nullable()
    val tier = varchar("tier", 50)
    val enabled = bool("enabled").default(true)
    val dailyLimit = integer("daily_limit").nullable()
    val config = jsonb("config")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
    val updatedBy = integer("updated_by").references(AdminUsers.id).nullable()
    
    override val primaryKey = PrimaryKey(id)
}

data class FeatureFlag(
    val id: Int,
    val featureKey: String,
    val featureName: String,
    val description: String?,
    val tier: String,
    val enabled: Boolean,
    val dailyLimit: Int?,
    val config: Map<String, Any>,
    val updatedAt: Instant,
    val updatedBy: Int?
)
```

#### Таблица: admin_actions_log
```sql
CREATE TABLE admin_actions_log (
    id SERIAL PRIMARY KEY,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    action_type VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL, -- 'user', 'subscription', 'feature', etc.
    target_id INTEGER,
    changes JSONB NOT NULL,
    reason TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_admin_actions_admin_id ON admin_actions_log(admin_id);
CREATE INDEX idx_admin_actions_created_at ON admin_actions_log(created_at);
CREATE INDEX idx_admin_actions_target ON admin_actions_log(target_type, target_id);
```

**Kotlin Entity:**
```kotlin
object AdminActionsLog : Table("admin_actions_log") {
    val id = integer("id").autoIncrement()
    val adminId = integer("admin_id").references(AdminUsers.id)
    val actionType = varchar("action_type", 100)
    val targetType = varchar("target_type", 50)
    val targetId = integer("target_id").nullable()
    val changes = jsonb("changes")
    val reason = text("reason").nullable()
    val ipAddress = varchar("ip_address", 45).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    
    override val primaryKey = PrimaryKey(id)
}

data class AdminAction(
    val id: Int,
    val adminId: Int,
    val actionType: String,
    val targetType: String,
    val targetId: Int?,
    val changes: Map<String, Any>,
    val reason: String?,
    val ipAddress: String?,
    val createdAt: Instant
)
```

#### Таблица: user_notes
```sql
CREATE TABLE user_notes (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    note_type VARCHAR(50) NOT NULL CHECK (note_type IN ('warning', 'ban', 'info', 'support')),
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_notes_user_id ON user_notes(user_id);
CREATE INDEX idx_user_notes_admin_id ON user_notes(admin_id);
CREATE INDEX idx_user_notes_created_at ON user_notes(created_at);
```

**Kotlin Entity:**
```kotlin
object UserNotes : Table("user_notes") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val adminId = integer("admin_id").references(AdminUsers.id)
    val noteType = varchar("note_type", 50)
    val content = text("content")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    
    override val primaryKey = PrimaryKey(id)
}

enum class NoteType {
    WARNING, BAN, INFO, SUPPORT
}

data class UserNote(
    val id: Int,
    val userId: Int,
    val adminId: Int,
    val noteType: NoteType,
    val content: String,
    val createdAt: Instant
)
```

**Kotlin Entity:**
```kotlin
object AIPromptCache : Table("ai_prompt_cache") {
    val id = integer("id").autoIncrement()
    val cacheKey = varchar("cache_key", 255).uniqueIndex()
    val systemPrompt = text("system_prompt")
    val cacheTokens = integer("cache_tokens")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val lastUsed = timestamp("last_used").defaultExpression(CurrentTimestamp())
    val usageCount = integer("usage_count").default(0)
    
    override val primaryKey = PrimaryKey(id)
}

data class PromptCache(
    val id: Int,
    val cacheKey: String,
    val systemPrompt: String,
    val cacheTokens: Int,
    val createdAt: Instant,
    val lastUsed: Instant,
    val usageCount: Int
)
```

**Kotlin Entity:**
```kotlin
object UserStats : Table("user_stats") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").uniqueIndex().references(Users.id, onDelete = ReferenceOption.CASCADE)
    val wordsSaved = integer("words_saved").default(0)
    val wordsMastered = integer("words_mastered").default(0)
    val cardsReviewed = integer("cards_reviewed").default(0)
    val correctReviews = integer("correct_reviews").default(0)
    val currentStreak = integer("current_streak").default(0)
    val longestStreak = integer("longest_streak").default(0)
    val lastActivityDate = date("last_activity_date").nullable()
    val aiExamplesGenerated = integer("ai_examples_generated").default(0)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
    
    override val primaryKey = PrimaryKey(id)
}

data class UserStat(
    val userId: Int,
    val wordsSaved: Int,
    val wordsMastered: Int,
    val cardsReviewed: Int,
    val correctReviews: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val lastActivityDate: LocalDate?,
    val aiExamplesGenerated: Int
)
```

### Database Migrations (Flyway)

**V1__initial_schema.sql:**
```sql
-- Users table
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    email_verified BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_created_at ON users(created_at);

-- Saved words table
CREATE TABLE saved_words (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    word VARCHAR(255) NOT NULL,
    translation VARCHAR(500),
    notes TEXT,
    saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_reviewed TIMESTAMP,
    mastery_level INTEGER DEFAULT 0 CHECK (mastery_level >= 0 AND mastery_level <= 5),
    UNIQUE(user_id, word)
);

CREATE INDEX idx_saved_words_user_id ON saved_words(user_id);
CREATE INDEX idx_saved_words_word ON saved_words(word);
CREATE INDEX idx_saved_words_saved_at ON saved_words(saved_at);
```

**V2__add_flashcards.sql:**
```sql
CREATE TABLE flashcards (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    word VARCHAR(255) NOT NULL,
    translation VARCHAR(500),
    definition TEXT,
    example TEXT,
    next_review TIMESTAMP NOT NULL,
    interval INTEGER NOT NULL DEFAULT 1,
    ease_factor FLOAT NOT NULL DEFAULT 2.5,
    repetitions INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, word)
);

CREATE INDEX idx_flashcards_user_id ON flashcards(user_id);
CREATE INDEX idx_flashcards_next_review ON flashcards(next_review);
CREATE INDEX idx_flashcards_user_next_review ON flashcards(user_id, next_review);
```

**V3__add_subscriptions.sql:**
```sql
CREATE TABLE subscriptions (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier VARCHAR(50) NOT NULL CHECK (tier IN ('free', 'student', 'premium')),
    status VARCHAR(50) NOT NULL CHECK (status IN ('active', 'expired', 'cancelled')),
    valid_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMP,
    activation_code VARCHAR(100),
    payment_method VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscriptions_user_id ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_status ON subscriptions(status);
CREATE UNIQUE INDEX idx_subscriptions_activation_code ON subscriptions(activation_code) WHERE activation_code IS NOT NULL;

-- Create default free subscription for all existing users
INSERT INTO subscriptions (user_id, tier, status)
SELECT id, 'free', 'active' FROM users;
```

**V4__add_usage_limits.sql:**
```sql
CREATE TABLE usage_limits (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature VARCHAR(100) NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    reset_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, feature, reset_at)
);

CREATE INDEX idx_usage_limits_user_feature ON usage_limits(user_id, feature);
CREATE INDEX idx_usage_limits_reset_at ON usage_limits(reset_at);
```

**V5__add_user_stats.sql:**
```sql
CREATE TABLE user_stats (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    words_saved INTEGER DEFAULT 0,
    words_mastered INTEGER DEFAULT 0,
    cards_reviewed INTEGER DEFAULT 0,
    correct_reviews INTEGER DEFAULT 0,
    current_streak INTEGER DEFAULT 0,
    longest_streak INTEGER DEFAULT 0,
    last_activity_date DATE,
    ai_examples_generated INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_stats_user_id ON user_stats(user_id);

-- Create stats for all existing users
INSERT INTO user_stats (user_id)
SELECT id FROM users;
```

**V6__add_ai_prompt_cache.sql:**
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

CREATE INDEX idx_ai_prompt_cache_key ON ai_prompt_cache(cache_key);
CREATE INDEX idx_ai_prompt_cache_last_used ON ai_prompt_cache(last_used);
```

**V7__add_admin_users.sql:**
```sql
CREATE TABLE admin_users (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL CHECK (role IN ('super_admin', 'moderator', 'support')),
    permissions JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by INTEGER REFERENCES admin_users(id)
);

CREATE INDEX idx_admin_users_user_id ON admin_users(user_id);
CREATE INDEX idx_admin_users_role ON admin_users(role);

-- Create first super admin (replace 1 with actual user ID)
-- INSERT INTO admin_users (user_id, role, permissions) VALUES 
-- (1, 'super_admin', '["users.view","users.edit","users.ban","subscriptions.view","subscriptions.edit","subscriptions.grant","features.view","features.edit","dictionaries.view","dictionaries.edit","logs.view","admin.manage"]'::jsonb);
```

**V8__add_feature_flags.sql:**
```sql
CREATE TABLE feature_flags (
    id SERIAL PRIMARY KEY,
    feature_key VARCHAR(100) NOT NULL,
    feature_name VARCHAR(255) NOT NULL,
    description TEXT,
    tier VARCHAR(50) NOT NULL CHECK (tier IN ('free', 'student', 'premium', 'all')),
    enabled BOOLEAN DEFAULT TRUE,
    daily_limit INTEGER,
    config JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by INTEGER REFERENCES admin_users(id)
);

CREATE INDEX idx_feature_flags_tier ON feature_flags(tier);
CREATE INDEX idx_feature_flags_enabled ON feature_flags(enabled);
CREATE INDEX idx_feature_flags_key_tier ON feature_flags(feature_key, tier);

-- Insert default AI features
INSERT INTO feature_flags (feature_key, feature_name, description, tier, enabled, daily_limit, config) VALUES
-- Free tier
('dict_free', 'Free Dictionary API', 'Basic dictionary lookups', 'free', true, null, '{"api": "free_dictionary"}'::jsonb),

-- Student tier
('ai_examples', 'AI Examples Generation', 'Generate example sentences using AI', 'student', true, 100, '{"model": "gemini", "max_examples": 3}'::jsonb),
('ai_exercises_batch', 'Batch Exercise Generation', 'Generate exercises for multiple words', 'student', true, 10, '{"model": "claude", "max_words": 20}'::jsonb),
('ai_sentence_check', 'Sentence Checking', 'Check if word is used correctly', 'student', true, 30, '{"model": "gemini"}'::jsonb),
('dict_merriam_webster', 'Merriam-Webster API', 'Premium dictionary with examples', 'student', true, 200, '{"api": "merriam_webster", "paid": true}'::jsonb),

-- Premium tier
('ai_examples', 'AI Examples Generation', 'Generate example sentences using AI', 'premium', true, null, '{"model": "claude", "max_examples": 5}'::jsonb),
('ai_exercises_batch', 'Batch Exercise Generation', 'Generate exercises for multiple words', 'premium', true, null, '{"model": "claude", "max_words": 50}'::jsonb),
('ai_sentence_check', 'Sentence Checking', 'Check if word is used correctly', 'premium', true, null, '{"model": "claude"}'::jsonb),
('dict_merriam_webster', 'Merriam-Webster API', 'Premium dictionary with examples', 'premium', true, null, '{"api": "merriam_webster", "paid": true}'::jsonb),
('dict_oxford', 'Oxford Dictionaries API', 'Comprehensive British/American English', 'premium', false, null, '{"api": "oxford", "paid": true}'::jsonb),
('pronunciation_check', 'Pronunciation Checking', 'AI-powered pronunciation verification', 'premium', false, null, '{"model": "whisper"}'::jsonb);
```

**V9__add_admin_logs_and_notes.sql:**
```sql
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

CREATE INDEX idx_admin_actions_admin_id ON admin_actions_log(admin_id);
CREATE INDEX idx_admin_actions_created_at ON admin_actions_log(created_at);
CREATE INDEX idx_admin_actions_target ON admin_actions_log(target_type, target_id);

CREATE TABLE user_notes (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    note_type VARCHAR(50) NOT NULL CHECK (note_type IN ('warning', 'ban', 'info', 'support')),
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_notes_user_id ON user_notes(user_id);
CREATE INDEX idx_user_notes_admin_id ON user_notes(admin_id);
CREATE INDEX idx_user_notes_created_at ON user_notes(created_at);
```

**V10__add_user_ban_fields.sql:**
```sql
-- Add ban fields to users table
ALTER TABLE users ADD COLUMN is_banned BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN ban_reason TEXT;
ALTER TABLE users ADD COLUMN banned_at TIMESTAMP;
ALTER TABLE users ADD COLUMN banned_by INTEGER REFERENCES admin_users(id);
ALTER TABLE users ADD COLUMN ban_expires_at TIMESTAMP;

CREATE INDEX idx_users_is_banned ON users(is_banned);
CREATE INDEX idx_users_ban_expires_at ON users(ban_expires_at);
```

---

## 🔐 Авторизация и безопасность

### JWT Authentication

**Конфигурация:**
```kotlin
object JWTConfig {
    const val SECRET = System.getenv("JWT_SECRET") ?: "your-secret-key-change-in-production"
    const val ISSUER = "wordwaverise.com"
    const val AUDIENCE = "wordwaverise-api"
    const val REALM = "WordWaverise API"
    const val VALIDITY_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    
    fun generateToken(userId: Int, email: String): String {
        return JWT.create()
            .withAudience(AUDIENCE)
            .withIssuer(ISSUER)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + VALIDITY_MS))
            .sign(Algorithm.HMAC256(SECRET))
    }
    
    fun verifyToken(token: String): DecodedJWT {
        val verifier = JWT.require(Algorithm.HMAC256(SECRET))
            .withAudience(AUDIENCE)
            .withIssuer(ISSUER)
            .build()
        return verifier.verify(token)
    }
}
```

**Ktor Authentication Setup:**
```kotlin
fun Application.configureSecurity() {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = JWTConfig.REALM
            verifier(
                JWT.require(Algorithm.HMAC256(JWTConfig.SECRET))
                    .withAudience(JWTConfig.AUDIENCE)
                    .withIssuer(JWTConfig.ISSUER)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(JWTConfig.AUDIENCE)) {
                    val userId = credential.payload.getClaim("userId").asInt()
                    val email = credential.payload.getClaim("email").asString()
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Invalid or expired token")
            }
        }
    }
}
```

### Password Hashing

**Использование Password4j:**
```kotlin
object PasswordUtils {
    private val hasher = Password4j.getHasher()
    
    fun hashPassword(password: String): String {
        return Password.hash(password).withBcrypt().getResult()
    }
    
    fun verifyPassword(password: String, hash: String): Boolean {
        return Password.check(password, hash).withBcrypt()
    }
}
```

### Rate Limiting

**По IP адресу:**
```kotlin
fun Application.configureRateLimiting() {
    install(RateLimit) {
        // Global rate limit
        global {
            rateLimiter(limit = 100, refillPeriod = 1.minutes)
        }
        
        // API endpoints
        register("api") {
            rateLimiter(limit = 1000, refillPeriod = 1.hours)
        }
        
        // Auth endpoints (stricter)
        register("auth") {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
        
        // AI endpoints (very strict for free users)
        register("ai") {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
        }
    }
}
```

**По пользователю (в middleware):**
```kotlin
suspend fun checkUserRateLimit(userId: Int, feature: String): Boolean {
    val subscription = SubscriptionService.getUserSubscription(userId)
    val limits = getLimitsForTier(subscription.tier, feature)
    
    if (limits.unlimited) return true
    
    val usage = UsageLimitRepository.getOrCreate(userId, feature)
    
    // Reset if needed
    if (usage.resetAt < Clock.System.now()) {
        UsageLimitRepository.reset(userId, feature)
        return true
    }
    
    // Check limit
    return usage.count < limits.daily
}
```

### Input Validation

**Validation Utils:**
```kotlin
object ValidationUtils {
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$")
    
    fun isValidEmail(email: String): Boolean {
        return email.matches(emailRegex) && email.length <= 255
    }
    
    fun isValidPassword(password: String): Boolean {
        return password.length >= 8 && password.length <= 128
    }
    
    fun isValidWord(word: String): Boolean {
        return word.isNotBlank() && 
               word.length <= 255 && 
               word.all { it.isLetter() || it == '-' || it == '\'' }
    }
    
    fun sanitizeInput(input: String): String {
        return input.trim()
            .replace(Regex("<[^>]*>"), "") // Remove HTML tags
            .take(1000) // Max length
    }
}
```

**Request Validation Middleware:**
```kotlin
data class ValidationError(val field: String, val message: String)

suspend fun <T> validateRequest(
    call: ApplicationCall,
    validator: (T) -> List<ValidationError>
): T? {
    val request = try {
        call.receive<T>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
        return null
    }
    
    val errors = validator(request)
    if (errors.isNotEmpty()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("errors" to errors))
        return null
    }
    
    return request
}
```

---

## 📡 API Endpoints

### Authentication Endpoints

#### POST /api/auth/register
**Описание:** Регистрация нового пользователя

**Request:**
```json
{
  "email": "user@example.com",
  "password": "securePassword123"
}
```

**Validation:**
- Email: валидный формат, уникальный
- Password: минимум 8 символов

**Response 201 Created:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": 123,
    "email": "user@example.com",
    "createdAt": "2024-12-06T10:00:00Z",
    "subscription": {
      "tier": "free",
      "status": "active"
    }
  }
}
```

**Response 400 Bad Request:**
```json
{
  "errors": [
    {
      "field": "email",
      "message": "Email already exists"
    }
  ]
}
```

**Implementation:**
```kotlin
post("/register") {
    val request = validateRequest<RegisterRequest>(call) { req ->
        buildList {
            if (!ValidationUtils.isValidEmail(req.email)) {
                add(ValidationError("email", "Invalid email format"))
            }
            if (!ValidationUtils.isValidPassword(req.password)) {
                add(ValidationError("password", "Password must be at least 8 characters"))
            }
        }
    } ?: return@post
    
    // Check if user exists
    if (UserRepository.findByEmail(request.email) != null) {
        call.respond(HttpStatusCode.BadRequest, mapOf(
            "errors" to listOf(
                ValidationError("email", "Email already exists")
            )
        ))
        return@post
    }
    
    // Create user
    val passwordHash = PasswordUtils.hashPassword(request.password)
    val user = UserRepository.create(request.email, passwordHash)
    
    // Create default free subscription
    SubscriptionRepository.createFreeSubscription(user.id)
    
    // Create user stats
    UserStatsRepository.create(user.id)
    
    // Generate token
    val token = JWTConfig.generateToken(user.id, user.email)
    
    call.respond(HttpStatusCode.Created, AuthResponse(
        token = token,
        user = user.toApiModel()
    ))
}
```

---

#### POST /api/auth/login
**Описание:** Вход пользователя

**Request:**
```json
{
  "email": "user@example.com",
  "password": "securePassword123"
}
```

**Response 200 OK:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": 123,
    "email": "user@example.com",
    "createdAt": "2024-12-06T10:00:00Z",
    "subscription": {
      "tier": "free",
      "status": "active"
    }
  }
}
```

**Response 401 Unauthorized:**
```json
{
  "error": "Invalid credentials"
}
```

**Implementation:**
```kotlin
post("/login") {
    val request = call.receive<LoginRequest>()
    
    val user = UserRepository.findByEmail(request.email)
    if (user == null || !PasswordUtils.verifyPassword(request.password, user.passwordHash)) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
        return@post
    }
    
    if (!user.isActive) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Account is deactivated"))
        return@post
    }
    
    // Update last login
    UserRepository.updateLastLogin(user.id)
    
    // Generate token
    val token = JWTConfig.generateToken(user.id, user.email)
    
    call.respond(HttpStatusCode.OK, AuthResponse(
        token = token,
        user = user.toApiModel()
    ))
}
```

---

#### GET /api/auth/me
**Описание:** Получить информацию о текущем пользователе

**Headers:**
```
Authorization: Bearer {token}
```

**Response 200 OK:**
```json
{
  "id": 123,
  "email": "user@example.com",
  "createdAt": "2024-12-06T10:00:00Z",
  "subscription": {
    "tier": "student",
    "status": "active",
    "validUntil": "2025-12-06T10:00:00Z"
  },
  "stats": {
    "wordsSaved": 148,
    "wordsMastered": 67,
    "cardsReviewed": 523,
    "successRate": 78,
    "currentStreak": 7,
    "longestStreak": 21
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/me") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal?.payload?.getClaim("userId")?.asInt() ?: run {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        
        val user = UserRepository.findById(userId) ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
            return@get
        }
        
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        val stats = UserStatsRepository.findByUserId(userId)
        
        call.respond(HttpStatusCode.OK, UserDetailResponse(
            id = user.id,
            email = user.email,
            createdAt = user.createdAt,
            subscription = subscription?.toApiModel(),
            stats = stats?.toApiModel()
        ))
    }
}
```

---

### Word Endpoints

#### GET /api/words/search?query={word}
**Описание:** Поиск слова в словарях

**Parameters:**
- `query` (required): слово для поиска

**Response 200 OK:**
```json
{
  "word": "ephemeral",
  "phonetic": "/ɪˈfɛm(ə)r(ə)l/",
  "definitions": [
    {
      "partOfSpeech": "adjective",
      "definition": "lasting for a very short time",
      "translation": "эфемерный, недолговечный",
      "example": "The beauty of cherry blossoms is ephemeral."
    }
  ],
  "synonyms": ["transient", "fleeting", "momentary"],
  "antonyms": ["permanent", "eternal", "lasting"],
  "examples": [
    "Fashion trends are often ephemeral.",
    "The ephemeral nature of social media posts.",
    "Morning dew is an ephemeral phenomenon."
  ]
}
```

**Response 404 Not Found:**
```json
{
  "error": "Word not found"
}
```

**Implementation:**
```kotlin
get("/search") {
    val query = call.request.queryParameters["query"]?.lowercase() ?: run {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Query parameter required"))
        return@get
    }
    
    if (!ValidationUtils.isValidWord(query)) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid word format"))
        return@get
    }
    
    // Check cache first
    val cached = CacheService.getWordDefinition(query)
    if (cached != null) {
        call.respond(HttpStatusCode.OK, cached)
        return@get
    }
    
    // Fetch from dictionary services
    val result = DictionaryService.searchWord(query)
    if (result == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Word not found"))
        return@get
    }
    
    // Cache result
    CacheService.cacheWordDefinition(query, result, duration = 24.hours)
    
    call.respond(HttpStatusCode.OK, result)
}
```

---

#### POST /api/words/save
**Описание:** Сохранить слово в личный словарь

**Headers:**
```
Authorization: Bearer {token}
```

**Request:**
```json
{
  "word": "ephemeral",
  "translation": "эфемерный",
  "notes": "Часто используется в поэзии"
}
```

**Response 201 Created:**
```json
{
  "success": true,
  "savedWord": {
    "id": 456,
    "word": "ephemeral",
    "translation": "эфемерный",
    "notes": "Часто используется в поэзии",
    "savedAt": "2024-12-06T12:00:00Z",
    "masteryLevel": 0
  }
}
```

**Response 400 Bad Request (limit reached):**
```json
{
  "error": "Word limit reached for your tier",
  "limit": 100,
  "current": 100
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    post("/save") {
        val userId = call.getUserId()
        val request = call.receive<SaveWordRequest>()
        
        // Check tier limits
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        val limit = when (subscription?.tier) {
            SubscriptionTier.FREE -> 100
            SubscriptionTier.STUDENT -> 5000
            SubscriptionTier.PREMIUM -> Int.MAX_VALUE
            else -> 0
        }
        
        val currentCount = WordRepository.countByUserId(userId)
        if (currentCount >= limit) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Word limit reached for your tier",
                "limit" to limit,
                "current" to currentCount
            ))
            return@post
        }
        
        // Check if already saved
        if (WordRepository.existsByUserAndWord(userId, request.word)) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Word already saved"))
            return@post
        }
        
        // Save word
        val savedWord = WordRepository.create(
            userId = userId,
            word = request.word,
            translation = request.translation,
            notes = request.notes
        )
        
        // Update stats
        UserStatsRepository.incrementWordsSaved(userId)
        
        // Auto-create flashcard
        FlashcardRepository.createFromWord(userId, savedWord)
        
        call.respond(HttpStatusCode.Created, mapOf(
            "success" to true,
            "savedWord" to savedWord.toApiModel()
        ))
    }
}
```

---

#### GET /api/words/saved
**Описание:** Получить список сохранённых слов

**Headers:**
```
Authorization: Bearer {token}
```

**Query Parameters:**
- `page` (optional): номер страницы (default: 1)
- `limit` (optional): количество на странице (default: 50, max: 100)
- `sort` (optional): сортировка (newest, oldest, alphabetical)
- `search` (optional): поиск по слову

**Response 200 OK:**
```json
{
  "words": [
    {
      "id": 456,
      "word": "ephemeral",
      "translation": "эфемерный",
      "notes": "Часто используется в поэзии",
      "savedAt": "2024-12-06T12:00:00Z",
      "lastReviewed": null,
      "masteryLevel": 0
    },
    {
      "id": 457,
      "word": "serendipity",
      "translation": "счастливая случайность",
      "notes": null,
      "savedAt": "2024-12-06T11:00:00Z",
      "lastReviewed": "2024-12-06T14:00:00Z",
      "masteryLevel": 2
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 50,
    "total": 148,
    "pages": 3
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/saved") {
        val userId = call.getUserId()
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val sort = call.request.queryParameters["sort"] ?: "newest"
        val search = call.request.queryParameters["search"]
        
        val (words, total) = WordRepository.findByUserId(
            userId = userId,
            page = page,
            limit = limit,
            sort = sort,
            search = search
        )
        
        call.respond(HttpStatusCode.OK, mapOf(
            "words" to words.map { it.toApiModel() },
            "pagination" to mapOf(
                "page" to page,
                "limit" to limit,
                "total" to total,
                "pages" to (total + limit - 1) / limit
            )
        ))
    }
}
```

---

#### DELETE /api/words/saved/{word}
**Описание:** Удалить слово из сохранённых

**Headers:**
```
Authorization: Bearer {token}
```

**Response 200 OK:**
```json
{
  "success": true
}
```

**Response 404 Not Found:**
```json
{
  "error": "Word not found"
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    delete("/saved/{word}") {
        val userId = call.getUserId()
        val word = call.parameters["word"]?.lowercase() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Word parameter required"))
            return@delete
        }
        
        val deleted = WordRepository.deleteByUserAndWord(userId, word)
        if (!deleted) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Word not found"))
            return@delete
        }
        
        // Also delete associated flashcard
        FlashcardRepository.deleteByUserAndWord(userId, word)
        
        // Update stats
        UserStatsRepository.decrementWordsSaved(userId)
        
        call.respond(HttpStatusCode.OK, mapOf("success" to true))
    }
}
```

---

### Flashcard Endpoints

#### GET /api/flashcards/due
**Описание:** Получить карточки для повторения

**Headers:**
```
Authorization: Bearer {token}
```

**Response 200 OK:**
```json
{
  "cards": [
    {
      "id": 789,
      "word": "ephemeral",
      "translation": "эфемерный",
      "definition": "lasting for a very short time",
      "example": "The beauty of cherry blossoms is ephemeral.",
      "nextReview": "2024-12-06T12:00:00Z"
    },
    {
      "id": 790,
      "word": "serendipity",
      "translation": "счастливая случайность",
      "definition": "the occurrence of events by chance in a happy way",
      "example": "Meeting my best friend was pure serendipity.",
      "nextReview": "2024-12-06T13:00:00Z"
    }
  ],
  "totalDue": 15
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/due") {
        val userId = call.getUserId()
        val now = Clock.System.now()
        
        val cards = FlashcardRepository.findDueCards(userId, now)
        
        call.respond(HttpStatusCode.OK, mapOf(
            "cards" to cards.map { it.toApiModel() },
            "totalDue" to cards.size
        ))
    }
}
```

---

#### POST /api/flashcards/review
**Описание:** Отметить результат повторения карточки

**Headers:**
```
Authorization: Bearer {token}
```

**Request:**
```json
{
  "cardId": 789,
  "difficulty": "easy"
}
```

**Difficulty values:**
- `again` - забыл (0)
- `hard` - сложно (1)
- `good` - нормально (2)
- `easy` - легко (3)

**Response 200 OK:**
```json
{
  "success": true,
  "nextReview": "2024-12-09T12:00:00Z",
  "interval": 3
}
```

**Implementation (SM-2 Algorithm):**
```kotlin
authenticate("auth-jwt") {
    post("/review") {
        val userId = call.getUserId()
        val request = call.receive<ReviewCardRequest>()
        
        val card = FlashcardRepository.findById(request.cardId) ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Card not found"))
            return@post
        }
        
        if (card.userId != userId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return@post
        }
        
        // SM-2 Algorithm
        val quality = when (request.difficulty) {
            "again" -> 0
            "hard" -> 1
            "good" -> 2
            "easy" -> 3
            else -> 2
        }
        
        var easeFactor = card.easeFactor
        var interval = card.interval
        var repetitions = card.repetitions
        
        if (quality >= 2) {
            // Correct answer
            repetitions++
            interval = when (repetitions) {
                1 -> 1
                2 -> 6
                else -> (interval * easeFactor).roundToInt()
            }
            easeFactor = (easeFactor + (0.1 - (3 - quality) * (0.08 + (3 - quality) * 0.02)))
                .coerceIn(1.3f, 2.5f)
        } else {
            // Incorrect answer
            repetitions = 0
            interval = 1
        }
        
        val nextReview = Clock.System.now() + interval.days
        
        // Update card
        FlashcardRepository.update(
            cardId = card.id,
            nextReview = nextReview,
            interval = interval,
            easeFactor = easeFactor,
            repetitions = repetitions
        )
        
        // Update stats
        UserStatsRepository.incrementCardsReviewed(userId)
        if (quality >= 2) {
            UserStatsRepository.incrementCorrectReviews(userId)
        }
        
        // Update mastery level in saved words
        val masteryLevel = calculateMasteryLevel(repetitions, easeFactor)
        WordRepository.updateMasteryLevel(userId, card.word, masteryLevel)
        
        call.respond(HttpStatusCode.OK, mapOf(
            "success" to true,
            "nextReview" to nextReview,
            "interval" to interval
        ))
    }
}

fun calculateMasteryLevel(repetitions: Int, easeFactor: Float): Int {
    return when {
        repetitions == 0 -> 0
        repetitions < 3 -> 1
        repetitions < 6 -> 2
        repetitions < 10 -> 3
        repetitions < 20 -> 4
        else -> 5
    }.coerceIn(0, 5)
}
```

---

### AI Endpoints (Gemini & Claude)

#### POST /api/ai/examples
**Описание:** Генерация примеров использования слова через AI

**Headers:**
```
Authorization: Bearer {token}
```

**Request:**
```json
{
  "word": "ephemeral",
  "context": "nature",
  "model": "gemini"
}
```

**Query Parameters:**
- `model` (optional): "gemini" | "claude" (default: зависит от тарифа)

**Response 200 OK:**
```json
{
  "examples": [
    "The beauty of cherry blossoms is ephemeral, lasting only a few weeks each spring.",
    "Fireflies create ephemeral lights that dance through the summer night.",
    "Morning dew is an ephemeral phenomenon that disappears with the rising sun."
  ],
  "model": "claude-haiku-4.5",
  "generatedAt": "2024-12-06T15:00:00Z",
  "cached": false
}
```

**Response 403 Forbidden (not Premium/Student):**
```json
{
  "error": "AI features are only available for Student and Premium users",
  "tier": "free"
}
```

**Response 429 Too Many Requests (limit reached):**
```json
{
  "error": "Daily AI request limit reached",
  "limit": 100,
  "resetAt": "2024-12-07T00:00:00Z"
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    post("/examples") {
        val userId = call.getUserId()
        val request = call.receive<GenerateExamplesRequest>()
        
        // Check subscription tier
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        if (subscription?.tier == SubscriptionTier.FREE) {
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to "AI features are only available for Student and Premium users",
                "tier" to "free"
            ))
            return@post
        }
        
        // Check rate limit
        if (!checkUserRateLimit(userId, "ai_examples")) {
            val resetAt = UsageLimitRepository.getResetTime(userId, "ai_examples")
            call.respond(HttpStatusCode.TooManyRequests, mapOf(
                "error" to "Daily AI request limit reached",
                "limit" to getLimitForTier(subscription.tier, "ai_examples"),
                "resetAt" to resetAt
            ))
            return@post
        }
        
        // Determine model based on tier or request
        val model = when {
            request.model != null -> request.model
            subscription.tier == SubscriptionTier.PREMIUM -> "claude"
            subscription.tier == SubscriptionTier.STUDENT -> "gemini"
            else -> "claude"
        }
        
        // Check cache first
        val cacheKey = "ai_examples:${request.word}:${request.context ?: ""}:$model"
        val cached = CacheService.get<AIExamplesResponse>(cacheKey)
        if (cached != null) {
            call.respond(HttpStatusCode.OK, cached.copy(cached = true))
            return@post
        }
        
        // Generate examples via AI
        val examples = when (model) {
            "claude" -> ClaudeService.generateExamples(request.word, request.context)
            "gemini" -> GeminiService.generateExamples(request.word, request.context)
            else -> ClaudeService.generateExamples(request.word, request.context)
        }
        
        val response = AIExamplesResponse(
            examples = examples,
            model = model,
            generatedAt = Clock.System.now(),
            cached = false
        )
        
        // Cache result
        CacheService.set(cacheKey, response, duration = 7.days)
        
        // Increment usage
        UsageLimitRepository.increment(userId, "ai_examples")
        UserStatsRepository.incrementAIExamplesGenerated(userId)
        
        call.respond(HttpStatusCode.OK, response)
    }
}
```

---

#### POST /api/ai/exercises/batch
**Описание:** Batch генерация упражнений для списка слов (оптимизировано с кэшированием)

**Headers:**
```
Authorization: Bearer {token}
```

**Request:**
```json
{
  "words": ["ephemeral", "serendipity", "eloquent"],
  "exerciseTypes": ["fill_blank", "multiple_choice", "sentence_completion"],
  "difficulty": "intermediate"
}
```

**Response 200 OK:**
```json
{
  "exercises": [
    {
      "word": "ephemeral",
      "type": "fill_blank",
      "question": "The beauty of cherry blossoms is ________, lasting only a few weeks.",
      "answer": "ephemeral",
      "options": null
    },
    {
      "word": "ephemeral",
      "type": "multiple_choice",
      "question": "Which word means 'lasting for a very short time'?",
      "answer": "ephemeral",
      "options": ["eternal", "ephemeral", "permanent", "lasting"]
    },
    {
      "word": "serendipity",
      "type": "sentence_completion",
      "question": "Complete: Meeting my best friend was pure ________",
      "answer": "serendipity",
      "options": null
    }
  ],
  "totalTokens": 1250,
  "cachedTokens": 800,
  "model": "claude-haiku-4.5",
  "generatedAt": "2024-12-06T15:00:00Z"
}
```

**Response 403 Forbidden (not Premium/Student):**
```json
{
  "error": "Batch exercises are only available for Student and Premium users",
  "tier": "free"
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    post("/exercises/batch") {
        val userId = call.getUserId()
        val request = call.receive<BatchExercisesRequest>()
        
        // Validation
        if (request.words.size > 50) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Maximum 50 words per batch request"
            ))
            return@post
        }
        
        // Check subscription tier
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        if (subscription?.tier == SubscriptionTier.FREE) {
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to "Batch exercises are only available for Student and Premium users",
                "tier" to "free"
            ))
            return@post
        }
        
        // Check rate limit (batch counts as 5 regular requests)
        val batchCost = 5
        if (!checkUserRateLimit(userId, "ai_examples", cost = batchCost)) {
            val resetAt = UsageLimitRepository.getResetTime(userId, "ai_examples")
            call.respond(HttpStatusCode.TooManyRequests, mapOf(
                "error" to "Daily AI request limit reached",
                "resetAt" to resetAt
            ))
            return@post
        }
        
        // Generate exercises using Claude with prompt caching
        val result = ClaudeService.generateExercisesBatch(
            words = request.words,
            exerciseTypes = request.exerciseTypes,
            difficulty = request.difficulty
        )
        
        // Increment usage
        UsageLimitRepository.increment(userId, "ai_examples", batchCost)
        
        call.respond(HttpStatusCode.OK, result)
    }
}
```

---

#### POST /api/ai/check-sentence
**Описание:** Проверка правильности использования слова в предложении

**Headers:**
```
Authorization: Bearer {token}
```

**Request:**
```json
{
  "word": "ephemeral",
  "sentence": "The moment was very ephemeral and disappeared quickly.",
  "model": "claude"
}
```

**Response 200 OK:**
```json
{
  "correct": true,
  "explanation": "The word 'ephemeral' is used correctly. It means something that lasts for a very short time, which matches the context of 'disappeared quickly'.",
  "correction": null,
  "alternatives": [
    "The moment was fleeting and disappeared quickly.",
    "The ephemeral moment vanished in an instant."
  ],
  "model": "claude-haiku-4.5",
  "checkedAt": "2024-12-06T15:00:00Z"
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    post("/check-sentence") {
        val userId = call.getUserId()
        val request = call.receive<CheckSentenceRequest>()
        
        // Check subscription tier
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        if (subscription?.tier == SubscriptionTier.FREE) {
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to "Sentence checking is only available for Student and Premium users"
            ))
            return@post
        }
        
        // Check rate limit
        if (!checkUserRateLimit(userId, "sentence_checks")) {
            val resetAt = UsageLimitRepository.getResetTime(userId, "sentence_checks")
            call.respond(HttpStatusCode.TooManyRequests, mapOf(
                "error" to "Daily sentence check limit reached",
                "resetAt" to resetAt
            ))
            return@post
        }
        
        // Determine model
        val model = request.model ?: "claude"
        
        // Check sentence
        val result = when (model) {
            "claude" -> ClaudeService.checkSentence(request.word, request.sentence)
            "gemini" -> GeminiService.checkSentence(request.word, request.sentence)
            else -> ClaudeService.checkSentence(request.word, request.sentence)
        }
        
        // Increment usage
        UsageLimitRepository.increment(userId, "sentence_checks")
        
        call.respond(HttpStatusCode.OK, result)
    }
}
```

**AIService Implementation (Gemini):**
```kotlin
object GeminiService {
    private val gemini = GoogleGenerativeAI(
        apiKey = System.getenv("GEMINI_API_KEY") ?: throw IllegalStateException("GEMINI_API_KEY not set")
    )
    
    private val model = gemini.getGenerativeModel("gemini-2.0-flash-exp")
    
    suspend fun generateExamples(word: String, context: String? = null): List<String> {
        val prompt = buildString {
            append("Generate 3 example sentences using the word \"$word\"")
            if (context != null) {
                append(" in the context of $context")
            }
            append(". ")
            append("Requirements:\n")
            append("1. Examples should be simple and natural for intermediate English learners\n")
            append("2. Use different contexts (formal, informal, everyday)\n")
            append("3. Each sentence should be 10-20 words\n")
            append("4. Return ONLY the 3 sentences, one per line, without numbering or extra text")
        }
        
        try {
            val response = model.generateContent(prompt)
            val text = response.text ?: throw Exception("Empty response from Gemini")
            
            val examples = text.trim()
                .split("\n")
                .filter { it.isNotBlank() }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("1. ").removePrefix("2. ").removePrefix("3. ") }
                .take(3)
            
            if (examples.size < 3) {
                throw Exception("Gemini returned less than 3 examples")
            }
            
            return examples
        } catch (e: Exception) {
            logger.error("Failed to generate AI examples with Gemini", e)
            throw Exception("Failed to generate examples: ${e.message}")
        }
    }
    
    suspend fun checkSentence(word: String, sentence: String): SentenceCheckResult {
        val prompt = """
            Check if the word "$word" is used correctly in this sentence:
            "$sentence"
            
            Provide:
            1. Is it correct? (yes/no)
            2. If incorrect, what's wrong?
            3. A corrected version if needed
            4. Alternative ways to use the word
            
            Format your response as JSON:
            {
              "correct": true/false,
              "explanation": "...",
              "correction": "..." or null,
              "alternatives": ["...", "..."]
            }
        """.trimIndent()
        
        try {
            val response = model.generateContent(prompt)
            val text = response.text ?: throw Exception("Empty response from Gemini")
            
            // Extract JSON from response (handle markdown code blocks)
            val jsonText = text.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString<SentenceCheckResult>(jsonText).copy(
                model = "gemini-2.0-flash-exp",
                checkedAt = Clock.System.now()
            )
            
        } catch (e: Exception) {
            logger.error("Failed to check sentence with Gemini", e)
            throw Exception("Failed to check sentence: ${e.message}")
        }
    }
}
```

**ClaudeService Implementation (with Prompt Caching):**
```kotlin
object ClaudeService {
    private val client = Anthropic(
        apiKey = System.getenv("ANTHROPIC_API_KEY") ?: throw IllegalStateException("ANTHROPIC_API_KEY not set")
    )
    
    private const val MODEL = "claude-haiku-4-5-20251001"
    
    /**
     * System prompt for exercise generation (will be cached)
     */
    private val EXERCISE_SYSTEM_PROMPT = """
        You are an expert English language teacher creating educational exercises for intermediate learners.
        
        Your task is to generate high-quality exercises that help students learn and practice English vocabulary.
        
        Exercise Types:
        1. fill_blank: Create a sentence with a blank where the word should go
        2. multiple_choice: Create a question with 4 options (one correct, three distractors)
        3. sentence_completion: Give a sentence start that the student completes with the word
        4. definition_match: Match the word to its correct definition
        5. synonym_antonym: Identify synonyms or antonyms
        
        Guidelines:
        - Keep language natural and appropriate for intermediate learners
        - Ensure exercises are clear and unambiguous
        - Make distractors plausible but clearly wrong
        - Use varied contexts (formal, informal, everyday situations)
        - Each sentence should be 10-25 words
        
        You will receive requests to generate exercises for specific words and must respond in JSON format.
    """.trimIndent()
    
    /**
     * Get or create cached system prompt
     */
    private suspend fun getCachedSystemPrompt(): String {
        val cacheKey = "exercise_generation_v1"
        
        return transaction {
            val cached = AIPromptCache
                .select { AIPromptCache.cacheKey eq cacheKey }
                .singleOrNull()
            
            if (cached != null) {
                // Update usage stats
                AIPromptCache.update({ AIPromptCache.id eq cached[AIPromptCache.id] }) {
                    it[lastUsed] = Clock.System.now().toJavaInstant()
                    it[usageCount] = cached[usageCount] + 1
                }
                cached[AIPromptCache.systemPrompt]
            } else {
                // Create new cache entry
                AIPromptCache.insert {
                    it[AIPromptCache.cacheKey] = cacheKey
                    it[systemPrompt] = EXERCISE_SYSTEM_PROMPT
                    it[cacheTokens] = estimateTokens(EXERCISE_SYSTEM_PROMPT)
                }
                EXERCISE_SYSTEM_PROMPT
            }
        }
    }
    
    suspend fun generateExamples(word: String, context: String? = null): List<String> {
        val prompt = buildString {
            append("Generate 3 example sentences using the word \"$word\"")
            if (context != null) {
                append(" in the context of $context")
            }
            append(".\n\n")
            append("Requirements:\n")
            append("- Simple and natural for intermediate English learners\n")
            append("- Different contexts (formal, informal, everyday)\n")
            append("- Each sentence 10-20 words\n")
            append("- Return ONLY 3 sentences, one per line, no numbering")
        }
        
        try {
            val message = client.messages.create(
                model = MODEL,
                maxTokens = 500,
                messages = listOf(
                    MessageParam(
                        role = Role.USER,
                        content = prompt
                    )
                )
            )
            
            val text = message.content.firstOrNull()?.text ?: throw Exception("Empty response from Claude")
            
            val examples = text.trim()
                .split("\n")
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .take(3)
            
            if (examples.size < 3) {
                throw Exception("Claude returned less than 3 examples")
            }
            
            return examples
        } catch (e: Exception) {
            logger.error("Failed to generate AI examples with Claude", e)
            throw Exception("Failed to generate examples: ${e.message}")
        }
    }
    
    suspend fun generateExercisesBatch(
        words: List<String>,
        exerciseTypes: List<String>,
        difficulty: String
    ): BatchExercisesResponse {
        val systemPrompt = getCachedSystemPrompt()
        
        val userPrompt = buildString {
            appendLine("Generate exercises for the following words:")
            appendLine()
            words.forEachIndexed { index, word ->
                appendLine("${index + 1}. $word")
            }
            appendLine()
            appendLine("Exercise types to generate: ${exerciseTypes.joinToString(", ")}")
            appendLine("Difficulty level: $difficulty")
            appendLine()
            appendLine("For each word, generate ONE exercise of EACH requested type.")
            appendLine()
            appendLine("Respond in JSON format:")
            appendLine("""
                {
                  "exercises": [
                    {
                      "word": "word",
                      "type": "exercise_type",
                      "question": "the question or sentence",
                      "answer": "correct answer",
                      "options": ["option1", "option2", "option3", "option4"] or null
                    }
                  ]
                }
            """.trimIndent())
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            val message = client.messages.create(
                model = MODEL,
                maxTokens = 4000,
                system = listOf(
                    SystemMessage(
                        type = "text",
                        text = systemPrompt,
                        cacheControl = CacheControl(type = "ephemeral") // Enable prompt caching
                    )
                ),
                messages = listOf(
                    MessageParam(
                        role = Role.USER,
                        content = userPrompt
                    )
                )
            )
            
            val duration = System.currentTimeMillis() - startTime
            
            val text = message.content.firstOrNull()?.text ?: throw Exception("Empty response from Claude")
            
            // Extract JSON from response
            val jsonText = text.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val json = Json { ignoreUnknownKeys = true }
            val response = json.decodeFromString<BatchExercisesRawResponse>(jsonText)
            
            // Get token usage from response
            val usage = message.usage
            val totalTokens = usage.inputTokens + usage.outputTokens
            val cachedTokens = usage.cacheReadInputTokens ?: 0
            
            logger.info(
                "Batch exercise generation completed: words=${words.size}, " +
                "exercises=${response.exercises.size}, tokens=$totalTokens, " +
                "cached=$cachedTokens, duration=${duration}ms"
            )
            
            return BatchExercisesResponse(
                exercises = response.exercises,
                totalTokens = totalTokens,
                cachedTokens = cachedTokens,
                model = MODEL,
                generatedAt = Clock.System.now()
            )
            
        } catch (e: Exception) {
            logger.error("Failed to generate batch exercises with Claude", e)
            throw Exception("Failed to generate exercises: ${e.message}")
        }
    }
    
    suspend fun checkSentence(word: String, sentence: String): SentenceCheckResult {
        val prompt = """
            Check if the word "$word" is used correctly in this sentence:
            "$sentence"
            
            Analyze:
            1. Is the word used correctly grammatically and contextually?
            2. Does the usage match the word's meaning?
            3. Is the sentence natural for an English speaker?
            
            Provide:
            - correct: true/false
            - explanation: Brief explanation of why it's correct or incorrect
            - correction: Corrected sentence if needed (null if correct)
            - alternatives: 2 alternative ways to use the word correctly
            
            Respond in JSON format:
            {
              "correct": true/false,
              "explanation": "...",
              "correction": "..." or null,
              "alternatives": ["...", "..."]
            }
        """.trimIndent()
        
        try {
            val message = client.messages.create(
                model = MODEL,
                maxTokens = 800,
                messages = listOf(
                    MessageParam(
                        role = Role.USER,
                        content = prompt
                    )
                )
            )
            
            val text = message.content.firstOrNull()?.text ?: throw Exception("Empty response from Claude")
            
            // Extract JSON from response
            val jsonText = text.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString<SentenceCheckResult>(jsonText).copy(
                model = MODEL,
                checkedAt = Clock.System.now()
            )
            
        } catch (e: Exception) {
            logger.error("Failed to check sentence with Claude", e)
            throw Exception("Failed to check sentence: ${e.message}")
        }
    }
    
    /**
     * Estimate token count (rough approximation: 1 token ≈ 4 characters)
     */
    private fun estimateTokens(text: String): Int {
        return (text.length / 4.0).toInt()
    }
}

// Data classes
@Serializable
data class AIExamplesResponse(
    val examples: List<String>,
    val model: String,
    val generatedAt: Instant,
    val cached: Boolean = false
)

@Serializable
data class BatchExercisesRequest(
    val words: List<String>,
    val exerciseTypes: List<String>,
    val difficulty: String
)

@Serializable
data class Exercise(
    val word: String,
    val type: String,
    val question: String,
    val answer: String,
    val options: List<String>? = null
)

@Serializable
data class BatchExercisesRawResponse(
    val exercises: List<Exercise>
)

@Serializable
data class BatchExercisesResponse(
    val exercises: List<Exercise>,
    val totalTokens: Int,
    val cachedTokens: Int,
    val model: String,
    val generatedAt: Instant
)

@Serializable
data class CheckSentenceRequest(
    val word: String,
    val sentence: String,
    val model: String? = null
)

@Serializable
data class SentenceCheckResult(
    val correct: Boolean,
    val explanation: String,
    val correction: String? = null,
    val alternatives: List<String>,
    val model: String? = null,
    val checkedAt: Instant? = null
)
```

---

### Subscription Endpoints

#### GET /api/subscription/status
**Описание:** Получить статус подписки пользователя

**Headers:**
```
Authorization: Bearer {token}
```

**Response 200 OK:**
```json
{
  "tier": "student",
  "status": "active",
  "validFrom": "2024-12-01T00:00:00Z",
  "validUntil": "2025-12-01T00:00:00Z",
  "limits": {
    "savedWords": {
      "used": 148,
      "limit": 5000
    },
    "aiRequests": {
      "used": 45,
      "limit": 100,
      "resetAt": "2024-12-07T00:00:00Z"
    },
    "pronunciationChecks": {
      "used": 12,
      "limit": 50,
      "resetAt": "2024-12-07T00:00:00Z"
    },
    "sentenceChecks": {
      "used": 8,
      "limit": 30,
      "resetAt": "2024-12-07T00:00:00Z"
    }
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/status") {
        val userId = call.getUserId()
        
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
            ?: SubscriptionRepository.createFreeSubscription(userId)
        
        val wordCount = WordRepository.countByUserId(userId)
        val wordLimit = when (subscription.tier) {
            SubscriptionTier.FREE -> 100
            SubscriptionTier.STUDENT -> 5000
            SubscriptionTier.PREMIUM -> Int.MAX_VALUE
        }
        
        val limits = mapOf(
            "savedWords" to mapOf(
                "used" to wordCount,
                "limit" to wordLimit
            ),
            "aiRequests" to getLimitStatus(userId, "ai_examples", subscription.tier),
            "pronunciationChecks" to getLimitStatus(userId, "pronunciation_checks", subscription.tier),
            "sentenceChecks" to getLimitStatus(userId, "sentence_checks", subscription.tier)
        )
        
        call.respond(HttpStatusCode.OK, mapOf(
            "tier" to subscription.tier.name.lowercase(),
            "status" to subscription.status.name.lowercase(),
            "validFrom" to subscription.validFrom,
            "validUntil" to subscription.validUntil,
            "limits" to limits
        ))
    }
}

fun getLimitStatus(userId: Int, feature: String, tier: SubscriptionTier): Map<String, Any> {
    val limit = getLimitForTier(tier, feature)
    
    if (limit == Int.MAX_VALUE) {
        return mapOf(
            "used" to 0,
            "limit" to "unlimited"
        )
    }
    
    val usage = UsageLimitRepository.getCurrent(userId, feature)
    val resetAt = UsageLimitRepository.getResetTime(userId, feature)
    
    return mapOf(
        "used" to usage,
        "limit" to limit,
        "resetAt" to resetAt
    )
}

fun getLimitForTier(tier: SubscriptionTier, feature: String): Int {
    return when (tier) {
        SubscriptionTier.FREE -> when (feature) {
            "ai_examples" -> 0
            "pronunciation_checks" -> 0
            "sentence_checks" -> 0
            "premium_dictionary" -> 0
            else -> 0
        }
        SubscriptionTier.STUDENT -> when (feature) {
            "ai_examples" -> 100
            "pronunciation_checks" -> 50
            "sentence_checks" -> 30
            "premium_dictionary" -> 200
            else -> 100
        }
        SubscriptionTier.PREMIUM -> Int.MAX_VALUE
    }
}
```

---

#### POST /api/subscription/activate-code
**Описание:** Активация Student подписки по промокоду

**Headers:**
```
Authorization: Bearer {token}
```

**Request:**
```json
{
  "code": "STUDENT-MSU-2024-A7K9"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "tier": "student",
  "validUntil": "2025-12-06T00:00:00Z",
  "message": "Student subscription activated successfully"
}
```

**Response 400 Bad Request:**
```json
{
  "error": "Invalid activation code"
}
```

**Response 409 Conflict:**
```json
{
  "error": "This code has already been used"
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    post("/activate-code") {
        val userId = call.getUserId()
        val request = call.receive<ActivateCodeRequest>()
        
        // Validate code format
        if (!request.code.matches(Regex("^STUDENT-[A-Z0-9]+-\\d{4}-[A-Z0-9]{4}$"))) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Invalid activation code format"
            ))
            return@post
        }
        
        // Check if code exists and is unused
        val existingSubscription = SubscriptionRepository.findByActivationCode(request.code)
        if (existingSubscription != null) {
            call.respond(HttpStatusCode.Conflict, mapOf(
                "error" to "This code has already been used"
            ))
            return@post
        }
        
        // Check if code is valid (could be from a codes database)
        if (!StudentCodeRepository.isValidCode(request.code)) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Invalid activation code"
            ))
            return@post
        }
        
        // Deactivate current subscription
        SubscriptionRepository.deactivateForUser(userId)
        
        // Create new student subscription (1 year)
        val validUntil = Clock.System.now() + 365.days
        val subscription = SubscriptionRepository.create(
            userId = userId,
            tier = SubscriptionTier.STUDENT,
            validUntil = validUntil,
            activationCode = request.code
        )
        
        // Mark code as used
        StudentCodeRepository.markAsUsed(request.code, userId)
        
        call.respond(HttpStatusCode.OK, mapOf(
            "success" to true,
            "tier" to "student",
            "validUntil" to validUntil,
            "message" to "Student subscription activated successfully"
        ))
    }
}
```

---

### User Stats Endpoints

#### GET /api/user/stats
**Описание:** Получить статистику пользователя

**Headers:**
```
Authorization: Bearer {token}
```

**Response 200 OK:**
```json
{
  "wordsSaved": 148,
  "wordsMastered": 67,
  "cardsReviewed": 523,
  "correctReviews": 408,
  "successRate": 78,
  "currentStreak": 7,
  "longestStreak": 21,
  "aiExamplesGenerated": 45,
  "lastActivityDate": "2024-12-06"
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/stats") {
        val userId = call.getUserId()
        
        val stats = UserStatsRepository.findByUserId(userId)
            ?: UserStatsRepository.create(userId)
        
        val successRate = if (stats.cardsReviewed > 0) {
            (stats.correctReviews.toFloat() / stats.cardsReviewed * 100).toInt()
        } else {
            0
        }
        
        call.respond(HttpStatusCode.OK, mapOf(
            "wordsSaved" to stats.wordsSaved,
            "wordsMastered" to stats.wordsMastered,
            "cardsReviewed" to stats.cardsReviewed,
            "correctReviews" to stats.correctReviews,
            "successRate" to successRate,
            "currentStreak" to stats.currentStreak,
            "longestStreak" to stats.longestStreak,
            "aiExamplesGenerated" to stats.aiExamplesGenerated,
            "lastActivityDate" to stats.lastActivityDate
        ))
    }
}
```

---

## 👑 Admin API Endpoints

### Authentication & Permissions

**Admin Middleware:**
```kotlin
// Check if user is admin
suspend fun ApplicationCall.requireAdmin(): AdminUser {
    val userId = getUserId()
    val admin = AdminRepository.findByUserId(userId)
        ?: throw ForbiddenException("Admin access required")
    
    if (!admin.isActive) {
        throw ForbiddenException("Admin account is deactivated")
    }
    
    return admin
}

// Check specific permission
suspend fun ApplicationCall.requirePermission(permission: String) {
    val admin = requireAdmin()
    
    if (admin.role == AdminRole.SUPER_ADMIN) return // Super admin has all permissions
    
    if (!admin.permissions.contains(permission)) {
        throw ForbiddenException("Permission denied: $permission")
    }
}

// Log admin action
suspend fun logAdminAction(
    adminId: Int,
    actionType: String,
    targetType: String,
    targetId: Int?,
    changes: Map<String, Any>,
    reason: String?,
    ipAddress: String?
) {
    AdminActionsLogRepository.create(
        adminId = adminId,
        actionType = actionType,
        targetType = targetType,
        targetId = targetId,
        changes = changes,
        reason = reason,
        ipAddress = ipAddress
    )
}
```

---

### User Management

#### GET /api/admin/users
**Описание:** Получить список пользователей с фильтрами

**Permissions Required:** `users.view`

**Headers:**
```
Authorization: Bearer {admin_token}
```

**Query Parameters:**
- `page` (optional): номер страницы (default: 1)
- `limit` (optional): количество на странице (default: 50, max: 100)
- `search` (optional): поиск по email
- `tier` (optional): фильтр по тарифу (free/student/premium)
- `banned` (optional): показать только забаненных (true/false)
- `sort` (optional): сортировка (newest/oldest/email)

**Response 200 OK:**
```json
{
  "users": [
    {
      "id": 123,
      "email": "user@example.com",
      "createdAt": "2024-12-06T10:00:00Z",
      "lastLogin": "2024-12-10T15:30:00Z",
      "isActive": true,
      "isBanned": false,
      "subscription": {
        "tier": "student",
        "status": "active",
        "validUntil": "2025-12-06T10:00:00Z"
      },
      "stats": {
        "wordsSaved": 148,
        "cardsReviewed": 523
      }
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 50,
    "total": 1543,
    "pages": 31
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/admin/users") {
        call.requirePermission("users.view")
        
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val search = call.request.queryParameters["search"]
        val tier = call.request.queryParameters["tier"]
        val banned = call.request.queryParameters["banned"]?.toBooleanStrictOrNull()
        val sort = call.request.queryParameters["sort"] ?: "newest"
        
        val (users, total) = UserRepository.findAll(
            page = page,
            limit = limit,
            search = search,
            tier = tier,
            banned = banned,
            sort = sort
        )
        
        call.respond(HttpStatusCode.OK, mapOf(
            "users" to users.map { it.toAdminApiModel() },
            "pagination" to mapOf(
                "page" to page,
                "limit" to limit,
                "total" to total,
                "pages" to (total + limit - 1) / limit
            )
        ))
    }
}
```

---

#### GET /api/admin/users/{userId}
**Описание:** Детальная информация о пользователе

**Permissions Required:** `users.view`

**Response 200 OK:**
```json
{
  "user": {
    "id": 123,
    "email": "user@example.com",
    "createdAt": "2024-12-06T10:00:00Z",
    "lastLogin": "2024-12-10T15:30:00Z",
    "isActive": true,
    "isBanned": false,
    "banReason": null,
    "bannedAt": null,
    "banExpiresAt": null
  },
  "subscription": {
    "tier": "student",
    "status": "active",
    "validFrom": "2024-12-01T00:00:00Z",
    "validUntil": "2025-12-01T00:00:00Z",
    "activationCode": "STUDENT-MSU-2024-A7K9"
  },
  "stats": {
    "wordsSaved": 148,
    "wordsMastered": 67,
    "cardsReviewed": 523,
    "currentStreak": 7,
    "aiExamplesGenerated": 45
  },
  "usage": {
    "ai_examples": {
      "used": 45,
      "limit": 100,
      "resetAt": "2024-12-07T00:00:00Z"
    }
  },
  "notes": [
    {
      "id": 1,
      "adminEmail": "admin@wordwaverise.com",
      "noteType": "info",
      "content": "User requested subscription extension",
      "createdAt": "2024-12-05T14:30:00Z"
    }
  ]
}
```

---

#### PUT /api/admin/users/{userId}/subscription
**Описание:** Изменить подписку пользователя вручную

**Permissions Required:** `subscriptions.edit`

**Request:**
```json
{
  "tier": "premium",
  "validUntil": "2025-12-31T23:59:59Z",
  "reason": "Granted premium for beta testing"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "subscription": {
    "tier": "premium",
    "status": "active",
    "validUntil": "2025-12-31T23:59:59Z"
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    put("/admin/users/{userId}/subscription") {
        call.requirePermission("subscriptions.edit")
        val admin = call.requireAdmin()
        val userId = call.parameters["userId"]?.toIntOrNull() ?: return@put call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID")
        )
        
        val request = call.receive<UpdateSubscriptionRequest>()
        
        // Validate
        val tier = try {
            SubscriptionTier.valueOf(request.tier.uppercase())
        } catch (e: Exception) {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid tier"))
        }
        
        // Deactivate old subscription
        SubscriptionRepository.deactivateForUser(userId)
        
        // Create new subscription
        val subscription = SubscriptionRepository.create(
            userId = userId,
            tier = tier,
            validUntil = request.validUntil,
            paymentMethod = "admin_grant"
        )
        
        // Log action
        logAdminAction(
            adminId = admin.id,
            actionType = "subscription.update",
            targetType = "user",
            targetId = userId,
            changes = mapOf(
                "old_tier" to "previous",
                "new_tier" to tier.name,
                "valid_until" to request.validUntil
            ),
            reason = request.reason,
            ipAddress = call.request.origin.remoteHost
        )
        
        call.respond(HttpStatusCode.OK, mapOf(
            "success" to true,
            "subscription" to subscription.toApiModel()
        ))
    }
}
```

---

#### POST /api/admin/users/{userId}/ban
**Описание:** Забанить пользователя

**Permissions Required:** `users.ban`

**Request:**
```json
{
  "reason": "Violation of terms of service",
  "duration": 2592000,
  "note": "Multiple spam reports"
}
```

**Parameters:**
- `duration` (optional): длительность бана в секундах (null = permanent)

**Response 200 OK:**
```json
{
  "success": true,
  "user": {
    "isBanned": true,
    "banReason": "Violation of terms of service",
    "banExpiresAt": "2025-01-10T00:00:00Z"
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    post("/admin/users/{userId}/ban") {
        call.requirePermission("users.ban")
        val admin = call.requireAdmin()
        val userId = call.parameters["userId"]?.toIntOrNull() ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID")
        )
        
        val request = call.receive<BanUserRequest>()
        
        val banExpiresAt = if (request.duration != null) {
            Clock.System.now() + request.duration.seconds
        } else {
            null // Permanent
        }
        
        // Update user
        UserRepository.ban(
            userId = userId,
            reason = request.reason,
            bannedBy = admin.id,
            expiresAt = banExpiresAt
        )
        
        // Add note
        UserNotesRepository.create(
            userId = userId,
            adminId = admin.id,
            noteType = NoteType.BAN,
            content = request.note ?: request.reason
        )
        
        // Log action
        logAdminAction(
            adminId = admin.id,
            actionType = "user.ban",
            targetType = "user",
            targetId = userId,
            changes = mapOf(
                "reason" to request.reason,
                "expires_at" to banExpiresAt
            ),
            reason = request.reason,
            ipAddress = call.request.origin.remoteHost
        )
        
        call.respond(HttpStatusCode.OK, mapOf(
            "success" to true,
            "user" to UserRepository.findById(userId)?.toAdminApiModel()
        ))
    }
}
```

---

#### DELETE /api/admin/users/{userId}/ban
**Описание:** Разбанить пользователя

**Permissions Required:** `users.ban`

**Response 200 OK:**
```json
{
  "success": true
}
```

---

#### POST /api/admin/users/{userId}/notes
**Описание:** Добавить заметку о пользователе

**Permissions Required:** `users.view`

**Request:**
```json
{
  "noteType": "info",
  "content": "User reported payment issue, investigating"
}
```

**Response 201 Created:**
```json
{
  "success": true,
  "note": {
    "id": 15,
    "noteType": "info",
    "content": "User reported payment issue, investigating",
    "createdAt": "2024-12-11T10:00:00Z"
  }
}
```

---

### Feature Flags Management

#### GET /api/admin/features
**Описание:** Получить все настройки функций

**Permissions Required:** `features.view`

**Response 200 OK:**
```json
{
  "features": [
    {
      "id": 1,
      "featureKey": "ai_examples",
      "featureName": "AI Examples Generation",
      "tier": "student",
      "enabled": true,
      "dailyLimit": 100,
      "config": {
        "model": "gemini",
        "max_examples": 3
      },
      "updatedAt": "2024-12-06T10:00:00Z"
    },
    {
      "id": 2,
      "featureKey": "ai_examples",
      "featureName": "AI Examples Generation",
      "tier": "premium",
      "enabled": true,
      "dailyLimit": null,
      "config": {
        "model": "claude",
        "max_examples": 5
      },
      "updatedAt": "2024-12-06T10:00:00Z"
    }
  ]
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    get("/admin/features") {
        call.requirePermission("features.view")
        
        val features = FeatureFlagsRepository.findAll()
        
        call.respond(HttpStatusCode.OK, mapOf(
            "features" to features.map { it.toApiModel() }
        ))
    }
}
```

---

#### PUT /api/admin/features/{featureId}
**Описание:** Обновить настройки функции

**Permissions Required:** `features.edit`

**Request:**
```json
{
  "enabled": false,
  "dailyLimit": 50,
  "config": {
    "model": "claude",
    "max_examples": 3
  },
  "reason": "Reducing costs, switching to Claude"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "feature": {
    "id": 1,
    "featureKey": "ai_examples",
    "tier": "student",
    "enabled": false,
    "dailyLimit": 50,
    "config": {
      "model": "claude",
      "max_examples": 3
    }
  }
}
```

**Implementation:**
```kotlin
authenticate("auth-jwt") {
    put("/admin/features/{featureId}") {
        call.requirePermission("features.edit")
        val admin = call.requireAdmin()
        val featureId = call.parameters["featureId"]?.toIntOrNull() ?: return@put call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Invalid feature ID")
        )
        
        val request = call.receive<UpdateFeatureRequest>()
        
        val oldFeature = FeatureFlagsRepository.findById(featureId) ?: return@put call.respond(
            HttpStatusCode.NotFound, mapOf("error" to "Feature not found")
        )
        
        // Update feature
        val updatedFeature = FeatureFlagsRepository.update(
            featureId = featureId,
            enabled = request.enabled,
            dailyLimit = request.dailyLimit,
            config = request.config,
            updatedBy = admin.id
        )
        
        // Log action
        logAdminAction(
            adminId = admin.id,
            actionType = "feature.update",
            targetType = "feature",
            targetId = featureId,
            changes = mapOf(
                "old_enabled" to oldFeature.enabled,
                "new_enabled" to request.enabled,
                "old_limit" to oldFeature.dailyLimit,
                "new_limit" to request.dailyLimit,
                "old_config" to oldFeature.config,
                "new_config" to request.config
            ),
            reason = request.reason,
            ipAddress = call.request.origin.remoteHost
        )
        
        call.respond(HttpStatusCode.OK, mapOf(
            "success" to true,
            "feature" to updatedFeature.toApiModel()
        ))
    }
}
```

---

#### POST /api/admin/features
**Описание:** Создать новую настройку функции

**Permissions Required:** `features.edit`

**Request:**
```json
{
  "featureKey": "ai_translation",
  "featureName": "AI Translation",
  "description": "Translate words to multiple languages",
  "tier": "premium",
  "enabled": true,
  "dailyLimit": null,
  "config": {
    "model": "gemini",
    "languages": ["es", "fr", "de", "it"]
  }
}
```

**Response 201 Created:**
```json
{
  "success": true,
  "feature": {
    "id": 25,
    "featureKey": "ai_translation",
    "tier": "premium",
    "enabled": true
  }
}
```

---

### Dictionary APIs Management

#### GET /api/admin/dictionaries
**Описание:** Получить настройки словарных API

**Permissions Required:** `dictionaries.view`

**Response 200 OK:**
```json
{
  "dictionaries": [
    {
      "id": 10,
      "featureKey": "dict_free",
      "featureName": "Free Dictionary API",
      "tier": "all",
      "enabled": true,
      "dailyLimit": null,
      "config": {
        "api": "free_dictionary",
        "paid": false
      }
    },
    {
      "id": 11,
      "featureKey": "dict_merriam_webster",
      "featureName": "Merriam-Webster API",
      "tier": "student",
      "enabled": true,
      "dailyLimit": 200,
      "config": {
        "api": "merriam_webster",
        "paid": true,
        "cost_per_1000": 0.002
      }
    }
  ]
}
```

---

#### PUT /api/admin/dictionaries/{dictionaryId}
**Описание:** Обновить настройки словарного API

**Request:**
```json
{
  "enabled": false,
  "dailyLimit": 100,
  "reason": "Cost optimization - limiting Merriam-Webster usage"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "dictionary": {
    "id": 11,
    "enabled": false,
    "dailyLimit": 100
  }
}
```

---

### Admin Actions Log

#### GET /api/admin/logs
**Описание:** Получить логи действий администраторов

**Permissions Required:** `logs.view`

**Query Parameters:**
- `page` (optional): номер страницы
- `limit` (optional): количество записей (default: 50)
- `adminId` (optional): фильтр по admin ID
- `actionType` (optional): тип действия
- `targetType` (optional): тип цели (user/subscription/feature)
- `from` (optional): с какой даты (ISO format)
- `to` (optional): до какой даты (ISO format)

**Response 200 OK:**
```json
{
  "logs": [
    {
      "id": 123,
      "admin": {
        "id": 1,
        "email": "admin@wordwaverise.com"
      },
      "actionType": "subscription.update",
      "targetType": "user",
      "targetId": 456,
      "changes": {
        "old_tier": "student",
        "new_tier": "premium",
        "valid_until": "2025-12-31T23:59:59Z"
      },
      "reason": "Beta tester reward",
      "ipAddress": "192.168.1.1",
      "createdAt": "2024-12-11T10:00:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 50,
    "total": 523
  }
}
```

---

### Statistics & Analytics

#### GET /api/admin/stats
**Описание:** Общая статистика платформы

**Permissions Required:** `users.view`

**Response 200 OK:**
```json
{
  "users": {
    "total": 1543,
    "active": 1234,
    "banned": 5,
    "newToday": 23,
    "newThisWeek": 156
  },
  "subscriptions": {
    "free": 1200,
    "student": 234,
    "premium": 109
  },
  "usage": {
    "aiRequestsToday": 4523,
    "aiRequestsThisMonth": 125678,
    "cachedTokensPercent": 78,
    "estimatedCostToday": 12.45,
    "estimatedCostThisMonth": 345.67
  },
  "features": {
    "enabled": 15,
    "disabled": 3
  }
}
```

---

### Service Configuration

**FeatureService.kt:**
```kotlin
object FeatureService {
    private val cache = mutableMapOf<String, FeatureFlag>()
    private var lastRefresh = Instant.DISTANT_PAST
    
    suspend fun isFeatureEnabled(userId: Int, featureKey: String): Boolean {
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        val tier = subscription?.tier?.name?.lowercase() ?: "free"
        
        val feature = getFeature(featureKey, tier) ?: return false
        return feature.enabled
    }
    
    suspend fun getFeatureConfig(userId: Int, featureKey: String): Map<String, Any> {
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        val tier = subscription?.tier?.name?.lowercase() ?: "free"
        
        val feature = getFeature(featureKey, tier) ?: return emptyMap()
        return feature.config
    }
    
    suspend fun getFeatureLimit(userId: Int, featureKey: String): Int? {
        val subscription = SubscriptionRepository.findActiveByUserId(userId)
        val tier = subscription?.tier?.name?.lowercase() ?: "free"
        
        val feature = getFeature(featureKey, tier) ?: return 0
        return feature.dailyLimit
    }
    
    private suspend fun getFeature(featureKey: String, tier: String): FeatureFlag? {
        // Refresh cache every 5 minutes
        if (Clock.System.now() - lastRefresh > 5.minutes) {
            refreshCache()
        }
        
        val cacheKey = "$featureKey:$tier"
        return cache[cacheKey]
    }
    
    private suspend fun refreshCache() {
        val features = FeatureFlagsRepository.findAll()
        cache.clear()
        features.forEach { feature ->
            cache["${feature.featureKey}:${feature.tier}"] = feature
        }
        lastRefresh = Clock.System.now()
    }
}
```

**Updated AI service calls:**
```kotlin
// Before making AI request
val config = FeatureService.getFeatureConfig(userId, "ai_examples")
val model = config["model"] as? String ?: "gemini"
val maxExamples = config["max_examples"] as? Int ?: 3

// Check if feature is enabled
if (!FeatureService.isFeatureEnabled(userId, "ai_examples")) {
    throw ForbiddenException("AI examples feature is not available for your tier")
}

// Check limit
val limit = FeatureService.getFeatureLimit(userId, "ai_examples")
if (limit != null && !checkUserRateLimit(userId, "ai_examples")) {
    throw RateLimitException("Daily limit reached")
}
```

---

## 🔒 Health & Monitoring Endpoints

#### GET /api/health
**Описание:** Health check endpoint

**Response 200 OK:**
```json
{
  "status": "ok",
  "version": "1.0.0",
  "timestamp": "2024-12-06T15:30:00Z",
  "uptime": 86400
}
```

**Implementation:**
```kotlin
get("/health") {
    call.respond(HttpStatusCode.OK, mapOf(
        "status" to "ok",
        "version" to "1.0.0",
        "timestamp" to Clock.System.now(),
        "uptime" to ManagementFactory.getRuntimeMXBean().uptime / 1000
    ))
}
```

---

#### GET /api/metrics
**Описание:** Метрики для мониторинга (требует admin роли)

**Response 200 OK:**
```json
{
  "database": {
    "users": 1543,
    "savedWords": 45678,
    "flashcards": 23456,
    "activeSubscriptions": 234
  },
  "cache": {
    "hitRate": 0.87,
    "size": 1234
  },
  "requests": {
    "total": 123456,
    "ratePerSecond": 45
  }
}
```

---

## 🤖 AI Models Strategy & Cost Optimization

### Стратегия выбора модели

**По тарифам (рекомендуемая конфигурация):**

```kotlin
fun getRecommendedModel(tier: SubscriptionTier, taskType: String): String {
    return when (tier) {
        SubscriptionTier.FREE -> "none" // No AI access
        SubscriptionTier.STUDENT -> when (taskType) {
            "examples" -> "gemini"          // Gemini для простых примеров
            "exercises" -> "claude"         // Claude для упражнений (более качественно)
            "check_sentence" -> "gemini"    // Gemini для проверки
            else -> "gemini"
        }
        SubscriptionTier.PREMIUM -> when (taskType) {
            "examples" -> "claude"          // Claude для всего (лучшее качество)
            "exercises" -> "claude"
            "check_sentence" -> "claude"
            else -> "claude"
        }
    }
}
```

**Альтернативная стратегия (по сложности задачи):**

```kotlin
fun getModelByComplexity(tier: SubscriptionTier, complexity: String): String {
    if (tier == SubscriptionTier.FREE) return "none"
    
    return when (complexity) {
        "simple" -> "gemini"      // Простые задачи: примеры, базовая проверка
        "medium" -> "claude"      // Средние: упражнения, детальная проверка
        "complex" -> "claude"     // Сложные: batch генерация, анализ
        else -> if (tier == SubscriptionTier.PREMIUM) "claude" else "gemini"
    }
}
```

### Оптимизация затрат на AI

#### 1. Prompt Caching (Claude)

**Преимущества:**
- Кэширование системных промптов снижает стоимость на 90%
- Кэш живёт 5 минут, обновляется при использовании
- Идеально для batch запросов с повторяющимися инструкциями

**Реализация:**
```kotlin
// System prompt помечается как cacheable
SystemMessage(
    type = "text",
    text = EXERCISE_SYSTEM_PROMPT,
    cacheControl = CacheControl(type = "ephemeral")
)

// При последующих запросах в течение 5 минут:
// - Input tokens (cached): ~1000 tokens → 90% дешевле
// - Input tokens (new): только новый контент
// - Output tokens: как обычно
```

**Экономия на примере batch запроса (50 слов):**
- Без кэша: ~1500 input tokens × $0.003 = $0.0045 за запрос
- С кэшем: ~1500 cached tokens × $0.0003 + 100 new tokens × $0.003 = $0.00075
- **Экономия: 83%**

#### 2. Response Caching (Redis)

**Стратегия кэширования:**

```kotlin
// Кэшируем результаты AI запросов
data class CacheStrategy(
    val ttl: Duration,
    val keyPattern: String
)

val CACHE_STRATEGIES = mapOf(
    "examples" to CacheStrategy(
        ttl = 7.days,
        keyPattern = "ai_examples:{word}:{context}:{model}"
    ),
    "exercises" to CacheStrategy(
        ttl = 7.days,
        keyPattern = "ai_exercises_batch:{words_hash}"
    ),
    "sentence_check" to CacheStrategy(
        ttl = 30.days,
        keyPattern = "sentence_check:{word}:{sentence_hash}"
    )
)

// Проверка кэша перед AI запросом
suspend fun <T> getCachedOrGenerate(
    cacheKey: String,
    ttl: Duration,
    generator: suspend () -> T
): Pair<T, Boolean> {
    val cached = CacheService.get<T>(cacheKey)
    if (cached != null) {
        return Pair(cached, true) // from cache
    }
    
    val result = generator()
    CacheService.set(cacheKey, result, ttl)
    return Pair(result, false) // generated
}
```

**Эффективность:**
- Popular words (top 1000): 80-90% cache hit rate
- Exercises: 60-70% cache hit rate (меньше вариаций)
- Sentence checks: 40-50% cache hit rate (уникальные предложения)

#### 3. Batch Processing

**Почему batch эффективнее:**

```
Отдельные запросы (10 слов):
- 10 запросов × 150 input tokens = 1500 tokens
- 10 запросов × 100 output tokens = 1000 tokens
- Total: 2500 tokens
- Latency: ~15-20 seconds (sequential)

Batch запрос (10 слов с кэшем):
- 1 запрос × (1000 cached + 200 new) input tokens = 1200 tokens (90% дешевле)
- 1 запрос × 800 output tokens = 800 tokens
- Total: 2000 tokens (20% экономия даже по токенам)
- Latency: ~3-5 seconds
```

**Рекомендации по batch size:**
```kotlin
object BatchLimits {
    const val MIN_WORDS = 3       // Минимум для эффективности batch
    const val MAX_WORDS = 50      // Максимум для одного запроса
    const val OPTIMAL_SIZE = 20   // Оптимальный размер batch
    
    fun calculateBatchCost(wordCount: Int): Int {
        // Cost in regular request equivalents
        return when {
            wordCount <= 5 -> 1
            wordCount <= 10 -> 3
            wordCount <= 20 -> 5
            wordCount <= 50 -> 10
            else -> 15
        }
    }
}
```

#### 4. Smart Rate Limiting

**Адаптивные лимиты в зависимости от кэша:**

```kotlin
suspend fun checkSmartRateLimit(
    userId: Int,
    feature: String,
    cacheHit: Boolean
): Boolean {
    val subscription = SubscriptionRepository.findActiveByUserId(userId)
    val limits = getLimitsForTier(subscription.tier, feature)
    
    if (limits.unlimited) return true
    
    // Если результат из кэша - не считаем в лимит (или считаем меньше)
    val cost = if (cacheHit) 0 else 1
    
    if (cost == 0) return true // Free cache access
    
    val usage = UsageLimitRepository.getCurrent(userId, feature)
    return usage + cost <= limits.daily
}
```

#### 5. Мониторинг затрат

**Отслеживание использования AI:**

```kotlin
// Добавить в UserStats
data class AIUsageStats(
    val totalRequests: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val totalTokens: Int,
    val cachedTokens: Int,
    val estimatedCost: Double
)

// Логирование каждого AI запроса
suspend fun logAIUsage(
    userId: Int,
    model: String,
    taskType: String,
    tokens: Int,
    cachedTokens: Int,
    fromCache: Boolean,
    duration: Long
) {
    AIUsageLog.insert {
        it[AIUsageLog.userId] = userId
        it[AIUsageLog.model] = model
        it[AIUsageLog.taskType] = taskType
        it[AIUsageLog.totalTokens] = tokens
        it[AIUsageLog.cachedTokens] = cachedTokens
        it[AIUsageLog.fromResponseCache] = fromCache
        it[AIUsageLog.duration] = duration
        it[AIUsageLog.cost] = calculateCost(model, tokens, cachedTokens)
    }
}
```

### Стоимость по моделям (примерные цены)

**Claude Haiku 4.5:**
- Input: $0.003 / 1K tokens ($0.0003 cached)
- Output: $0.015 / 1K tokens
- **90% экономия** на кэшированных токенах

**Gemini 2.0 Flash:**
- Input: $0.00001 / 1K tokens (очень дешево!)
- Output: $0.00004 / 1K tokens
- Кэширование: нет встроенного, только Redis
- **Преимущество:** Скорость и низкая стоимость

**Сравнение затрат (1000 AI запросов в день):**

Без оптимизации:
- Gemini 2.0 Flash: ~$0.05-0.10/день (очень дешево)
- Claude Haiku 4.5: ~$5-7/день

С полной оптимизацией (кэш + batch):
- Gemini 2.0 Flash: ~$0.02-0.05/день (90% экономия)
- Claude Haiku 4.5: ~$1-2/день (70-80% экономия)

**Рекомендация:** 
- Student tier → Gemini 2.0 Flash (максимальная экономия)
- Premium tier → Claude Haiku 4.5 (максимальное качество)
- Возможность переключения в админ-панели

### Рекомендуемая конфигурация

**Production Setup:**

```yaml
# config/ai.yaml
ai:
  default_model:
    free: none
    student: gemini
    premium: claude
  
  task_models:
    examples:
      student: gemini
      premium: claude
    exercises:
      student: claude
      premium: claude
    sentence_check:
      student: gemini
      premium: claude
  
  caching:
    examples_ttl: 7d
    exercises_ttl: 7d
    sentence_check_ttl: 30d
  
  batch:
    min_size: 3
    max_size: 50
    optimal_size: 20
  
  rate_limits:
    student:
      daily: 100
      per_minute: 5
    premium:
      daily: unlimited
      per_minute: 20
```

### Миграция между моделями

**Плавный переход без изменения API:**

```kotlin
// Клиент делает запрос без указания модели
POST /api/ai/examples
{
  "word": "ephemeral"
}

// Сервер автоматически выбирает модель
val model = getRecommendedModel(user.subscription.tier, "examples")
val result = when (model) {
    "claude" -> ClaudeService.generateExamples(word)
    "gemini" -> GeminiService.generateExamples(word)
    else -> throw ForbiddenException()
}

// Или клиент может явно указать (для A/B тестов)
POST /api/ai/examples
{
  "word": "ephemeral",
  "model": "claude"  // optional override
}
```

### Метрики для мониторинга

**Ключевые показатели:**
1. Cache hit rate (цель: >70%)
2. Average tokens per request (цель: <500)
3. Average response time (цель: <3s)
4. Cost per active user per day (цель: <$0.05)
5. Model preference by tier (для анализа)

---

## 🔄 External API Integration

### Dictionary Service

**DictionaryService.kt:**
```kotlin
object DictionaryService {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }
    
    suspend fun searchWord(word: String): WordDefinition? {
        return coroutineScope {
            // Parallel requests to multiple APIs
            val results = listOf(
                async { fetchFromFreeDictionary(word) },
                async { fetchFromMerriamWebster(word) },
                async { fetchFromWiktionary(word) }
            ).awaitAll().filterNotNull()
            
            if (results.isEmpty()) return@coroutineScope null
            
            // Merge results
            mergeDefinitions(results)
        }
    }
    
    private suspend fun fetchFromFreeDictionary(word: String): WordDefinition? {
        return try {
            val response = client.get("https://api.dictionaryapi.dev/api/v2/entries/en/$word")
            if (response.status != HttpStatusCode.OK) return null
            
            val data = response.body<List<FreeDictionaryResponse>>()
            parseFreeDictionaryResponse(data)
        } catch (e: Exception) {
            logger.error("Failed to fetch from Free Dictionary", e)
            null
        }
    }
    
    private suspend fun fetchFromMerriamWebster(word: String): WordDefinition? {
        val apiKey = System.getenv("MERRIAM_WEBSTER_API_KEY") ?: return null
        
        return try {
            val response = client.get("https://www.dictionaryapi.com/api/v3/references/collegiate/json/$word") {
                parameter("key", apiKey)
            }
            if (response.status != HttpStatusCode.OK) return null
            
            val data = response.body<List<MerriamWebsterResponse>>()
            parseMerriamWebsterResponse(data)
        } catch (e: Exception) {
            logger.error("Failed to fetch from Merriam-Webster", e)
            null
        }
    }
    
    private suspend fun fetchFromWiktionary(word: String): WordDefinition? {
        // Query local Wiktionary dump database
        return WiktionaryRepository.findByWord(word)
    }
    
    private fun mergeDefinitions(results: List<WordDefinition>): WordDefinition {
        // Merge logic: combine definitions, synonyms, antonyms, examples
        val allDefinitions = results.flatMap { it.definitions }.distinctBy { it.definition }
        val allSynonyms = results.flatMap { it.synonyms }.distinct()
        val allAntonyms = results.flatMap { it.antonyms }.distinct()
        val allExamples = results.flatMap { it.examples }.distinct().take(5)
        
        return WordDefinition(
            word = results.first().word,
            phonetic = results.firstNotNullOfOrNull { it.phonetic },
            definitions = allDefinitions,
            synonyms = allSynonyms,
            antonyms = allAntonyms,
            examples = allExamples
        )
    }
}
```

---

## 💾 Caching Strategy (Redis)

### Cache Service

**CacheService.kt:**
```kotlin
object CacheService {
    private val redis = RedisClient.create("redis://localhost:6379")
    private val connection = redis.connect()
    private val commands = connection.sync()
    
    private val json = Json {
        ignoreUnknownKeys = true
    }
    
    fun <T> get(key: String, type: KClass<T>): T? {
        return try {
            val value = commands.get(key) ?: return null
            json.decodeFromString(type.serializer(), value) as T
        } catch (e: Exception) {
            logger.error("Failed to get from cache", e)
            null
        }
    }
    
    inline fun <reified T> get(key: String): T? {
        return get(key, T::class)
    }
    
    fun <T> set(key: String, value: T, duration: Duration? = null) {
        try {
            val serialized = json.encodeToString(value)
            if (duration != null) {
                commands.setex(key, duration.inWholeSeconds, serialized)
            } else {
                commands.set(key, serialized)
            }
        } catch (e: Exception) {
            logger.error("Failed to set cache", e)
        }
    }
    
    fun delete(key: String) {
        try {
            commands.del(key)
        } catch (e: Exception) {
            logger.error("Failed to delete from cache", e)
        }
    }
    
    fun exists(key: String): Boolean {
        return try {
            commands.exists(key) > 0
        } catch (e: Exception) {
            false
        }
    }
    
    // Specific caching functions
    fun cacheWordDefinition(word: String, definition: WordDefinition, duration: Duration) {
        set("word:$word", definition, duration)
    }
    
    fun getWordDefinition(word: String): WordDefinition? {
        return get("word:$word")
    }
}
```

### Cache Keys Strategy

```
word:{word}                                - Word definitions (TTL: 24h)
ai_examples:{word}:{context}:{model}       - AI generated examples (TTL: 7d)
ai_exercises_batch:{words_hash}            - Batch exercises (TTL: 7d)
user:{userId}:subscription                 - User subscription (TTL: 1h)
user:{userId}:stats                        - User stats (TTL: 5m)
sentence_check:{word}:{sentence_hash}      - Sentence validations (TTL: 30d)
```

---

## 📊 Logging & Monitoring

### Logging Configuration

**logback.xml:**
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/wordwaverise.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/wordwaverise.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.wordwaverise" level="DEBUG"/>
    <logger name="io.ktor" level="INFO"/>
    <logger name="Exposed" level="INFO"/>
    
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### Structured Logging

```kotlin
object Logger {
    private val logger = LoggerFactory.getLogger("WordWaverise")
    
    fun logRequest(userId: Int?, endpoint: String, method: String, statusCode: Int, duration: Long) {
        logger.info(
            "REQUEST - userId={} endpoint={} method={} status={} duration={}ms",
            userId ?: "anonymous",
            endpoint,
            method,
            statusCode,
            duration
        )
    }
    
    fun logError(context: String, error: Throwable, metadata: Map<String, Any> = emptyMap()) {
        logger.error(
            "ERROR - context={} message={} metadata={}",
            context,
            error.message,
            metadata,
            error
        )
    }
    
    fun logAIRequest(userId: Int, word: String, success: Boolean, duration: Long) {
        logger.info(
            "AI_REQUEST - userId={} word={} success={} duration={}ms",
            userId,
            word,
            success,
            duration
        )
    }
}
```

---

## 📊 AI Models Comparison & Examples

### Сравнение качества ответов

#### Пример 1: Генерация примеров для слова "ephemeral"

**Gemini 2.0 Flash:**
```
1. The beauty of cherry blossoms is ephemeral, lasting only a few weeks each spring.
2. Social media fame can be quite ephemeral, disappearing as quickly as it arrives.
3. The morning dew created an ephemeral sparkle on the grass.
```

**Claude Haiku 4.5:**
```
1. The artist captured the ephemeral beauty of the sunset in her watercolor painting.
2. Childhood memories often feel ephemeral, fading like morning mist.
3. The pop-up restaurant's ephemeral existence made it even more sought after.
```

**Анализ:**
- Gemini: Более прямолинейные, академические примеры, очень быстрые (~0.5-1s)
- Claude: Более креативные, разнообразные контексты (~1-2s)
- Gemini в 20 раз дешевле при сравнимом качестве для простых задач

#### Пример 2: Batch генерация упражнений (3 слова)

**Request:**
```json
{
  "words": ["ephemeral", "serendipity", "eloquent"],
  "exerciseTypes": ["fill_blank", "multiple_choice"],
  "difficulty": "intermediate"
}
```

**Claude Response (с кэшем):**
```json
{
  "exercises": [
    {
      "word": "ephemeral",
      "type": "fill_blank",
      "question": "The beauty of cherry blossoms is ________, lasting only a few weeks.",
      "answer": "ephemeral",
      "options": null
    },
    {
      "word": "ephemeral",
      "type": "multiple_choice",
      "question": "Which word best describes something that lasts a very short time?",
      "answer": "ephemeral",
      "options": ["eternal", "ephemeral", "permanent", "enduring"]
    },
    {
      "word": "serendipity",
      "type": "fill_blank",
      "question": "Finding that rare book was pure ________, a happy accident.",
      "answer": "serendipity",
      "options": null
    },
    {
      "word": "serendipity",
      "type": "multiple_choice",
      "question": "What is 'serendipity'?",
      "answer": "serendipity",
      "options": ["bad luck", "serendipity", "hard work", "coincidence"]
    },
    {
      "word": "eloquent",
      "type": "fill_blank",
      "question": "Her ________ speech moved the entire audience to tears.",
      "answer": "eloquent",
      "options": null
    },
    {
      "word": "eloquent",
      "type": "multiple_choice",
      "question": "An eloquent speaker is someone who:",
      "answer": "speaks persuasively and expressively",
      "options": [
        "speaks very quietly",
        "speaks persuasively and expressively",
        "speaks in multiple languages",
        "speaks very quickly"
      ]
    }
  ],
  "totalTokens": 1450,
  "cachedTokens": 980,
  "model": "claude-haiku-4.5",
  "generatedAt": "2024-12-06T15:00:00Z"
}
```

**Token Analysis:**
- Input tokens (system prompt): ~980 (cached - 90% cheaper)
- Input tokens (user request): ~470 (regular price)
- Output tokens: ~1200 (regular price)
- **Total cost: ~$0.025** (vs ~$0.045 without cache)

#### Пример 3: Проверка предложения

**Request:**
```json
{
  "word": "ephemeral",
  "sentence": "The moment was very ephemeral and disappeared quickly."
}
```

**Claude Response:**
```json
{
  "correct": true,
  "explanation": "The word 'ephemeral' is used correctly. It means something that lasts for a very short time, which matches the context perfectly. However, the sentence is slightly redundant since 'ephemeral' already implies something brief.",
  "correction": null,
  "alternatives": [
    "The moment was ephemeral.",
    "The ephemeral moment vanished quickly."
  ],
  "model": "claude-haiku-4.5",
  "checkedAt": "2024-12-06T15:30:00Z"
}
```

**Gemini 2.0 Flash Response:**
```json
{
  "correct": true,
  "explanation": "Yes, 'ephemeral' is used correctly here to describe something short-lived. The sentence is grammatically correct.",
  "correction": null,
  "alternatives": [
    "The moment was fleeting and disappeared quickly.",
    "The transient moment vanished rapidly."
  ],
  "model": "gemini-2.0-flash-exp",
  "checkedAt": "2024-12-06T15:30:00Z"
}
```

**Анализ:**
- Claude: Более детальный анализ, указал на redundancy
- Gemini: Краткий, но точный ответ
- Оба дают корректную оценку и альтернативы

### Performance Benchmarks

**Single Request Latency:**
```
Gemini 2.0 Flash:
- Simple examples: 0.5-1.2s (очень быстро!)
- Sentence check: 0.8-1.5s

Claude Haiku 4.5:
- Simple examples: 0.8-1.5s
- Sentence check: 1.0-2.0s
- Batch (20 words): 3.5-5.0s
```

**Cost per 1000 requests:**
```
Gemini 2.0 Flash:
- Examples (avg 400 tokens): ~$0.02
- Sentence check (avg 600 tokens): ~$0.03

Claude Haiku 4.5:
- Examples (avg 400 tokens): ~$1.00
- Sentence check (avg 600 tokens): ~$1.50
- Batch with cache (20 words): ~$2.00 (vs $4.50 без кэша)
```

**Cache Performance:**
```
Redis (response cache):
- Hit latency: 5-15ms
- Hit rate: 70-85% (popular words)

Claude Prompt Cache:
- First request: ~3-4s
- Subsequent (within 5min): ~1-2s (cached prompt)
- Token reduction: 90% on system prompt
```

### A/B Testing Recommendations

**Тестируемые гипотезы:**

1. **Hypothesis 1: Model Quality**
   - Metric: User satisfaction (thumbs up/down)
   - Groups: 
     - A: All Gemini
     - B: All Claude
     - C: Mixed (current strategy)

2. **Hypothesis 2: Response Time**
   - Metric: Task completion rate
   - Groups:
     - A: Fast model priority (Haiku first)
     - B: Quality model priority (best for task)

3. **Hypothesis 3: Cost vs Value**
   - Metric: User retention & engagement
   - Groups:
     - A: Premium = Claude, Student = Gemini
     - B: Premium = Claude, Student = Claude (limited)
     - C: Both = Claude (unlimited Premium)

**Сбор данных:**
```kotlin
data class AIFeedback(
    val requestId: String,
    val userId: Int,
    val model: String,
    val taskType: String,
    val rating: Int, // 1-5 or thumbs up/down
    val responseTime: Long,
    val fromCache: Boolean,
    val comment: String?
)

// Эндпоинт для feedback
POST /api/ai/feedback
{
  "requestId": "req_abc123",
  "rating": 5,
  "comment": "Great examples!"
}
```

### Migration Path

**Phase 1: Testing (Weeks 1-2)**
```kotlin
// Enable both models, 50/50 split for Student tier
val model = if (Random.nextBoolean()) "claude" else "gemini"
```

**Phase 2: Analysis (Week 3)**
- Analyze feedback, costs, performance
- Determine optimal strategy

**Phase 3: Rollout (Week 4+)**
```kotlin
// Implement final strategy
val model = when (subscription.tier) {
    STUDENT -> "gemini" // or "claude" based on results
    PREMIUM -> "claude"
    else -> "none"
}
```

---

## 🚀 Deployment

### Docker Configuration

**Dockerfile:**
```dockerfile
FROM gradle:8.0-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle buildFatJar --no-daemon

FROM openjdk:17-slim
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: wordwaverise
      POSTGRES_USER: wordwaverise
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    restart: unless-stopped

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: wordwaverise
      DB_USER: wordwaverise
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      JWT_SECRET: ${JWT_SECRET}
      GEMINI_API_KEY: ${GEMINI_API_KEY}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      MERRIAM_WEBSTER_API_KEY: ${MERRIAM_WEBSTER_API_KEY}
    depends_on:
      - postgres
      - redis
    restart: unless-stopped

volumes:
  postgres_data:
  redis_data:
```

**.env:**
```
DB_PASSWORD=your_secure_password
JWT_SECRET=your_jwt_secret_key_change_in_production
GEMINI_API_KEY=your_gemini_api_key
ANTHROPIC_API_KEY=your_anthropic_api_key
MERRIAM_WEBSTER_API_KEY=your_merriam_webster_api_key
```

### Deployment Commands

```bash
# Build and start
docker-compose up -d --build

# View logs
docker-compose logs -f app

# Stop
docker-compose down

# Backup database
docker-compose exec postgres pg_dump -U wordwaverise wordwaverise > backup.sql

# Restore database
docker-compose exec -T postgres psql -U wordwaverise wordwaverise < backup.sql
```

---

## 📋 API Documentation (OpenAPI/Swagger)

### Swagger Configuration

```kotlin
fun Application.configureSwagger() {
    install(SwaggerUI) {
        swagger {
            swaggerUiPath = "/docs"
            swaggerJsonPath = "/docs/swagger.json"
            info {
                title = "WordWaverise API"
                version = "1.0.0"
                description = "API for WordWaverise - AI-powered English learning platform"
            }
            server {
                url = "https://api.wordwaverise.com"
                description = "Production server"
            }
            server {
                url = "http://localhost:8080"
                description = "Local development"
            }
        }
    }
}
```

Access documentation at: `https://api.wordwaverise.com/docs`

---

## ✅ Тестирование

### Unit Tests Example

```kotlin
class AuthServiceTest {
    private lateinit var database: Database
    private lateinit var authService: AuthService
    
    @BeforeTest
    fun setup() {
        database = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
        transaction {
            SchemaUtils.create(Users)
        }
        authService = AuthService()
    }
    
    @Test
    fun `register creates user successfully`() = runTest {
        val email = "test@example.com"
        val password = "securePass123"
        
        val user = authService.register(email, password)
        
        assertNotNull(user)
        assertEquals(email, user.email)
        assertTrue(PasswordUtils.verifyPassword(password, user.passwordHash))
    }
    
    @Test
    fun `register fails with existing email`() = runTest {
        val email = "test@example.com"
        authService.register(email, "password1")
        
        assertFailsWith<UserAlreadyExistsException> {
            authService.register(email, "password2")
        }
    }
}
```

### Integration Tests Example

```kotlin
class WordRoutesTest {
    @Test
    fun `search word returns definition`() = testApplication {
        val response = client.get("/api/words/search?query=hello")
        
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<WordDefinition>()
        assertEquals("hello", body.word)
        assertTrue(body.definitions.isNotEmpty())
    }
    
    @Test
    fun `save word requires authentication`() = testApplication {
        val response = client.post("/api/words/save") {
            contentType(ContentType.Application.Json)
            setBody(SaveWordRequest("hello", "привет"))
        }
        
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
```

---

## 📊 Performance Considerations

### Database Optimization

1. **Indexing:**
   - All foreign keys indexed
   - Frequently queried columns indexed
   - Composite indexes for common queries

2. **Connection Pooling:**
   ```kotlin
   HikariConfig().apply {
       jdbcUrl = "jdbc:postgresql://localhost:5432/wordwaverise"
       username = "wordwaverise"
       password = System.getenv("DB_PASSWORD")
       maximumPoolSize = 20
       minimumIdle = 5
       connectionTimeout = 30000
       idleTimeout = 600000
       maxLifetime = 1800000
   }
   ```

3. **Query Optimization:**
   - Use batch inserts where possible
   - Avoid N+1 queries
   - Use joins efficiently

### Caching Strategy

1. **Cache frequently accessed data:**
   - Word definitions (24 hours)
   - User subscriptions (1 hour)
   - AI examples (7 days)

2. **Cache invalidation:**
   - On user actions (save, delete)
   - Time-based expiration
   - Manual invalidation endpoints

### API Rate Limiting

1. **Global limits:**
   - 100 requests per minute per IP
   - 1000 requests per hour per user

2. **Endpoint-specific limits:**
   - Auth endpoints: 10/min
   - AI endpoints: 5/min (Free), 100/hour (Student)

---

## 🔒 Security Checklist

- [ ] HTTPS enabled (SSL certificate)
- [ ] JWT tokens with expiration
- [ ] Password hashing (Bcrypt)
- [ ] Input validation on all endpoints
- [ ] SQL injection prevention (parameterized queries)
- [ ] XSS protection (sanitize input)
- [ ] Rate limiting enabled
- [ ] CORS configured properly
- [ ] Environment variables for secrets
- [ ] Database backups automated
- [ ] Error messages don't leak sensitive info
- [ ] Dependency vulnerabilities scanned
- [ ] Admin endpoints protected (separate auth)
- [ ] Admin actions logged
- [ ] Permission system implemented
- [ ] Ban system functional
- [ ] Feature flags validated before use

---

## 📞 Контакты и поддержка

**Документация API:** https://api.wordwaverise.com/docs  
**GitHub Repository:** (to be added)  
**Support Email:** support@wordwaverise.com

---

**Версия документа:** 1.0  
**Последнее обновление:** 6 декабря 2024  
**Статус:** Готов к разработке

---

*Этот документ является техническим руководством для разработки серверной части WordWaverise. Все изменения должны согласовываться с командой разработки.*
