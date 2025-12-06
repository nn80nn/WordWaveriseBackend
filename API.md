# WordWaverise API Documentation

Base URL: `http://localhost:8080`

## Общая информация

### Формат ответов

Все API эндпоинты возвращают ответы в едином формате:

```json
{
  "status": "ok" | "error",
  "data": <данные ответа>,
  "message": "сообщение об ошибке (опционально)"
}
```

### Аутентификация

Защищенные эндпоинты требуют JWT токен в заголовке:
```
Authorization: Bearer <ваш_jwt_токен>
```

---

## Эндпоинты

### 1. Базовые

#### `GET /`
Простая проверка работоспособности сервера.

**Ответ:**
```
Hello World!
```

#### `GET /api/health`
Проверка состояния API.

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "status": "ok",
    "version": "1.0.0"
  }
}
```

#### `HEAD /api/health`
Быстрая проверка доступности (только заголовки, без тела ответа).

**Статус:** `200 OK`

---

### 2. Аутентификация

#### `POST /api/auth/register`
Регистрация нового пользователя.

**Тело запроса:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Требования:**
- Email должен быть валидным
- Пароль минимум 6 символов

**Ответ:** `201 Created`
```json
{
  "status": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "email": "user@example.com",
      "createdAt": "2025-12-06T10:00:00Z"
    }
  }
}
```

#### `POST /api/auth/login`
Вход в систему.

**Тело запроса:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "email": "user@example.com",
      "createdAt": "2025-12-06T10:00:00Z"
    }
  }
}
```

---

### 3. Поиск слов в словаре

#### `GET /api/words/search`
Базовый поиск слова в словаре (устаревший, для обратной совместимости).

**Параметры запроса:**
- `query` (обязательный) - слово для поиска

**Пример:** `/api/words/search?query=hello`

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "word": "hello",
    "meanings": [...],
    "phonetics": [...]
  }
}
```

#### `GET /api/words/details`
Расширенный поиск слова с агрегацией данных из нескольких источников.

**Параметры запроса:**
- `query` (обязательный) - слово для поиска

**Пример:** `/api/words/details?query=hello`

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "word": "hello",
    "phonetic": "/həˈloʊ/",
    "audioUrl": "https://api.dictionaryapi.dev/media/pronunciations/en/hello.mp3",
    "translation": "привет",
    "definitions": [
      {
        "partOfSpeech": "noun",
        "definition": "A greeting",
        "example": "She gave me a warm hello",
        "source": "FreeDictionaryAPI"
      }
    ],
    "synonyms": ["hi", "greetings"],
    "antonyms": ["goodbye"],
    "examples": ["Hello, how are you?"]
  }
}
```

---

### 4. Сохраненные слова (требуется аутентификация)

#### `POST /api/words/saved`
Сохранить слово в личный словарь.

**Требуется:** JWT токен

**Тело запроса:**
```json
{
  "word": "hello",
  "translation": "привет",
  "definition": "A greeting"
}
```

**Ответ:** `201 Created`
```json
{
  "status": "ok",
  "data": {
    "id": 1,
    "word": "hello",
    "translation": "привет",
    "definition": "A greeting",
    "savedAt": "2025-12-06T10:00:00Z"
  }
}
```

#### `POST /api/words/save`
Альтернативный эндпоинт для сохранения слова (устаревший, для обратной совместимости).

**Требуется:** JWT токен

**Тело запроса:** аналогично `/api/words/saved`

#### `GET /api/words/saved`
Получить все сохраненные слова пользователя.

**Требуется:** JWT токен

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "words": [
      {
        "id": 1,
        "word": "hello",
        "translation": "привет",
        "definition": "A greeting",
        "savedAt": "2025-12-06T10:00:00Z"
      }
    ]
  }
}
```

#### `DELETE /api/words/saved/{word}`
Удалить сохраненное слово.

**Требуется:** JWT токен

**Параметры пути:**
- `word` - слово для удаления

**Пример:** `/api/words/saved/hello`

**Ответ:**
```json
{
  "status": "ok",
  "data": "Word deleted successfully"
}
```

---

### 5. Флешкарты (требуется аутентификация)

Все эндпоинты флешкарт используют алгоритм интервального повторения (SM-2).

#### `POST /api/flashcards`
Создать новую флешкарту напрямую.

**Требуется:** JWT токен

**Тело запроса:**
```json
{
  "word": "hello",
  "translation": "привет",
  "definition": "A greeting",
  "example": "Hello, how are you?"
}
```

**Ответ:** `201 Created`
```json
{
  "status": "ok",
  "data": {
    "id": 1,
    "userId": 1,
    "savedWordId": null,
    "word": "hello",
    "translation": "привет",
    "definition": "A greeting",
    "example": "Hello, how are you?",
    "easeFactor": 2.5,
    "repetitions": 0,
    "interval": 0,
    "nextReview": "2025-12-06T10:00:00Z",
    "lastReviewed": null,
    "createdAt": "2025-12-06T10:00:00Z",
    "updatedAt": "2025-12-06T10:00:00Z"
  }
}
```

#### `POST /api/flashcards/create`
Создать флешкарту из сохраненного слова.

**Требуется:** JWT токен

**Тело запроса:**
```json
{
  "savedWordId": 1
}
```

**Ответ:** `201 Created` (аналогично предыдущему эндпоинту)

#### `GET /api/flashcards`
Получить все флешкарты пользователя.

**Требуется:** JWT токен

**Ответ:**
```json
{
  "status": "ok",
  "data": [
    {
      "id": 1,
      "userId": 1,
      "savedWordId": null,
      "word": "hello",
      "translation": "привет",
      "definition": "A greeting",
      "example": "Hello, how are you?",
      "easeFactor": 2.5,
      "repetitions": 0,
      "interval": 1,
      "nextReview": "2025-12-07T10:00:00Z",
      "lastReviewed": "2025-12-06T10:00:00Z",
      "createdAt": "2025-12-06T10:00:00Z",
      "updatedAt": "2025-12-06T10:00:00Z"
    }
  ]
}
```

#### `GET /api/flashcards/due`
Получить флешкарты, которые нужно повторить сейчас.

**Требуется:** JWT токен

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "cards": [
      {
        "id": 1,
        "word": "hello",
        "translation": "привет",
        "definition": "A greeting",
        "example": "Hello, how are you?",
        "nextReview": "2025-12-06T10:00:00Z",
        "daysUntilReview": 0
      }
    ],
    "totalDue": 1
  }
}
```

#### `PUT /api/flashcards/{id}`
Обновить прогресс изучения флешкарты.

**Требуется:** JWT токен

**Параметры пути:**
- `id` - ID флешкарты

**Тело запроса:**
```json
{
  "difficulty": "GOOD"
}
```

**Уровни сложности:**
- `AGAIN` - не помню (интервал сбрасывается)
- `HARD` - сложно (интервал увеличивается минимально)
- `GOOD` - хорошо (нормальное увеличение)
- `EASY` - легко (максимальное увеличение)

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "cardId": 1,
    "nextReview": "2025-12-08T10:00:00Z",
    "interval": 2,
    "message": "Flashcard reviewed successfully"
  }
}
```

#### `POST /api/flashcards/review`
Альтернативный эндпоинт для обзора флешкарты.

**Требуется:** JWT токен

**Тело запроса:**
```json
{
  "cardId": 1,
  "difficulty": "GOOD"
}
```

**Ответ:** аналогично `PUT /api/flashcards/{id}`

#### `GET /api/flashcards/statistics`
Получить статистику по флешкартам.

**Требуется:** JWT токен

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "totalCards": 50,
    "dueCards": 5,
    "learnedCards": 30,
    "newCards": 15,
    "reviewingCards": 20
  }
}
```

**Описание полей:**
- `totalCards` - всего флешкарт
- `dueCards` - карт для повторения сегодня
- `learnedCards` - изученные карты (интервал > 21 день)
- `newCards` - новые карты (повторений = 0)
- `reviewingCards` - карты в процессе изучения

#### `DELETE /api/flashcards/{id}`
Удалить флешкарту.

**Требуется:** JWT токен

**Параметры пути:**
- `id` - ID флешкарты

**Ответ:**
```json
{
  "status": "ok",
  "data": "Flashcard deleted successfully"
}
```

---

### 6. Управление кешем

#### `GET /api/cache/stats`
Получить статистику кеша.

**Ответ:**
```json
{
  "status": "ok",
  "data": {
    "size": 150,
    "hits": 320,
    "misses": 80,
    "hitRate": 0.8
  }
}
```

#### `POST /api/cache/clear`
Очистить кеш.

**Ответ:**
```json
{
  "status": "ok",
  "data": "Cache cleared successfully"
}
```

---

## Коды ошибок

| Код | Описание |
|-----|----------|
| 400 | Bad Request - некорректные данные запроса |
| 401 | Unauthorized - требуется аутентификация или неверный токен |
| 403 | Forbidden - доступ запрещен |
| 404 | Not Found - ресурс не найден |
| 500 | Internal Server Error - внутренняя ошибка сервера |

**Пример ответа с ошибкой:**
```json
{
  "status": "error",
  "data": null,
  "message": "Email and password are required"
}
```

---

## Примеры использования

### Регистрация и сохранение слова

```bash
# 1. Регистрация
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123"}'

# 2. Сохранение слова (используйте токен из ответа регистрации)
curl -X POST http://localhost:8080/api/words/saved \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{"word":"hello","translation":"привет"}'
```

### Работа с флешкартами

```bash
# 1. Создать флешкарту
curl -X POST http://localhost:8080/api/flashcards \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{"word":"hello","translation":"привет","example":"Hello world"}'

# 2. Получить карты для повторения
curl http://localhost:8080/api/flashcards/due \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"

# 3. Отметить повторение
curl -X PUT http://localhost:8080/api/flashcards/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{"difficulty":"GOOD"}'
```
