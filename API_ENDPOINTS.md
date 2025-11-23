# WordWaverise Backend - API Endpoints

Complete API reference for the WordWaverise backend.

## Base URL

```
http://localhost:8080
```

## Authentication

All endpoints marked with 🔒 require JWT authentication.

Include the JWT token in the `Authorization` header:
```
Authorization: Bearer {your_jwt_token}
```

---

## Word Search & Dictionary

### 1. Search for Word Definitions

Get word definitions from multiple dictionary sources with aggregation.

**Endpoint:** `GET /api/words/search`

**Query Parameters:**
- `query` (required): The word to search for

**Example:**
```bash
curl "http://localhost:8080/api/words/search?query=serendipity"
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "word": "serendipity",
    "phonetic": "/ˌserənˈdɪpɪti/",
    "audioUrl": "https://api.dictionaryapi.dev/media/...",
    "translation": "счастливая случайность",
    "definitions": [
      {
        "partOfSpeech": "noun",
        "definition": "The occurrence of events by chance in a happy way",
        "example": "Finding that book was pure serendipity",
        "source": "FreeDictionary"
      }
    ],
    "synonyms": ["chance", "luck", "fortune"],
    "antonyms": [],
    "examples": [
      "Finding that book was pure serendipity"
    ]
  }
}
```

### 2. Enhanced Word Details

Get comprehensive word data aggregated from multiple sources.

**Endpoint:** `GET /api/words/details`

**Query Parameters:**
- `query` (required): The word to search for

**Example:**
```bash
curl "http://localhost:8080/api/words/details?query=hello"
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "word": "hello",
    "phonetic": "/həˈloʊ/",
    "audioUrl": "https://...",
    "translation": "привет",
    "definitions": [
      {
        "partOfSpeech": "interjection",
        "definition": "Used as a greeting",
        "example": "Hello, how are you?",
        "source": "FreeDictionary"
      }
    ],
    "synonyms": ["hi", "hey", "greetings"],
    "antonyms": ["goodbye", "farewell"],
    "examples": ["Hello, how are you?", "She said hello"]
  }
}
```

---

## Saved Words 🔒

All saved words endpoints require authentication.

### 3. Get User's Saved Words

Retrieve all words saved by the authenticated user.

**Endpoint:** `GET /api/words/saved`

**Headers:**
```
Authorization: Bearer {token}
```

**Example:**
```bash
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/words/saved
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "words": [
      {
        "id": 1,
        "word": "serendipity",
        "translation": "счастливая случайность",
        "definition": "The occurrence of events by chance...",
        "savedAt": "2025-01-24T10:30:00Z"
      },
      {
        "id": 2,
        "word": "ephemeral",
        "translation": "эфемерный",
        "definition": "Lasting for a very short time",
        "savedAt": "2025-01-23T15:20:00Z"
      }
    ]
  }
}
```

### 4. Save a Word

Save a word to the user's collection.

**Endpoint:** `POST /api/words/saved`

**Headers:**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "word": "serendipity",
  "translation": "счастливая случайность",
  "definition": "The occurrence of events by chance in a happy way"
}
```

**Note:** `translation` and `definition` are optional but recommended for better flashcard creation.

**Example:**
```bash
curl -X POST http://localhost:8080/api/words/saved \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "word": "serendipity",
    "translation": "счастливая случайность",
    "definition": "The occurrence of events by chance in a happy way"
  }'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "id": 1,
    "word": "serendipity",
    "translation": "счастливая случайность",
    "definition": "The occurrence of events by chance in a happy way",
    "savedAt": "2025-01-24T10:30:00Z"
  }
}
```

**Status:** `201 Created`

### 5. Delete a Saved Word

Remove a word from the user's saved words.

**Endpoint:** `DELETE /api/words/saved/{word}`

**Headers:**
```
Authorization: Bearer {token}
```

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/words/saved/serendipity \
  -H "Authorization: Bearer {token}"
```

**Response:**
```json
{
  "status": "ok",
  "data": "Word deleted successfully"
}
```

---

## Flashcards 🔒

All flashcard endpoints require authentication.

### 6. Get User's Flashcards

Retrieve all flashcards for the authenticated user.

**Endpoint:** `GET /api/flashcards`

**Headers:**
```
Authorization: Bearer {token}
```

**Example:**
```bash
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/flashcards
```

**Response:**
```json
{
  "status": "ok",
  "data": [
    {
      "id": 1,
      "word": "hello",
      "translation": "привет",
      "definition": "Used as a greeting",
      "example": "Hello, how are you?",
      "nextReview": "2025-01-30T10:00:00Z",
      "daysUntilReview": 6
    },
    {
      "id": 2,
      "word": "goodbye",
      "translation": "до свидания",
      "definition": "Used when parting",
      "example": null,
      "nextReview": "2025-01-25T10:00:00Z",
      "daysUntilReview": 1
    }
  ]
}
```

### 7. Create a Flashcard

Create a new flashcard directly (without requiring a saved word).

**Endpoint:** `POST /api/flashcards`

**Headers:**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "word": "serendipity",
  "translation": "счастливая случайность",
  "definition": "The occurrence of events by chance in a happy way",
  "example": "Finding that book was pure serendipity"
}
```

**Note:** `definition` and `example` are optional.

**Example:**
```bash
curl -X POST http://localhost:8080/api/flashcards \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "word": "serendipity",
    "translation": "счастливая случайность",
    "definition": "The occurrence of events by chance in a happy way"
  }'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "id": 1,
    "word": "serendipity",
    "translation": "счастливая случайность",
    "definition": "The occurrence of events by chance in a happy way",
    "example": null,
    "nextReview": "2025-01-24T10:00:00Z",
    "daysUntilReview": 0
  }
}
```

**Status:** `201 Created`

### 8. Update Flashcard Progress

Update a flashcard's review status and schedule the next review.

**Endpoint:** `PUT /api/flashcards/{id}`

**Headers:**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "difficulty": "GOOD"
}
```

**Difficulty Options:**
- `AGAIN`: Forgot completely (resets to 1 day)
- `HARD`: Difficult to recall (shorter interval)
- `GOOD`: Good recall (standard interval)
- `EASY`: Perfect recall (longer interval)

**Example:**
```bash
curl -X PUT http://localhost:8080/api/flashcards/1 \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"difficulty": "GOOD"}'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "cardId": 1,
    "nextReview": "2025-01-30T10:00:00Z",
    "interval": 6,
    "message": "Nice! Next review in 6 days."
  }
}
```

### 9. Get Due Flashcards

Get all flashcards that are due for review.

**Endpoint:** `GET /api/flashcards/due`

**Headers:**
```
Authorization: Bearer {token}
```

**Example:**
```bash
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/flashcards/due
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "cards": [
      {
        "id": 1,
        "word": "hello",
        "translation": "привет",
        "definition": "Used as a greeting",
        "example": "Hello, how are you?",
        "nextReview": "2025-01-24T10:00:00Z",
        "daysUntilReview": 0
      }
    ],
    "totalDue": 1
  }
}
```

### 10. Get Flashcard Statistics

View learning progress and statistics.

**Endpoint:** `GET /api/flashcards/statistics`

**Headers:**
```
Authorization: Bearer {token}
```

**Example:**
```bash
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/flashcards/statistics
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "totalCards": 50,
    "dueCards": 12,
    "learnedCards": 25,
    "newCards": 8,
    "reviewingCards": 17
  }
}
```

**Card States:**
- **New Cards**: Never reviewed (repetitions = 0)
- **Reviewing Cards**: In learning phase (repetitions = 1-2)
- **Learned Cards**: Mastered (repetitions > 2)
- **Due Cards**: Ready for review now

### 11. Create Flashcard from Saved Word

Create a flashcard from an existing saved word.

**Endpoint:** `POST /api/flashcards/create`

**Headers:**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "savedWordId": 123
}
```

**Example:**
```bash
curl -X POST http://localhost:8080/api/flashcards/create \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"savedWordId": 123}'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "id": 1,
    "word": "hello",
    "translation": "привет",
    "definition": "Used as a greeting",
    "example": null,
    "nextReview": "2025-01-24T10:00:00Z",
    "daysUntilReview": 0
  }
}
```

**Status:** `201 Created`

### 12. Delete a Flashcard

Remove a flashcard from the user's deck.

**Endpoint:** `DELETE /api/flashcards/{id}`

**Headers:**
```
Authorization: Bearer {token}
```

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/flashcards/1 \
  -H "Authorization: Bearer {token}"
```

**Response:**
```json
{
  "status": "ok",
  "data": "Flashcard deleted successfully"
}
```

---

## Cache Management

### 13. Get Cache Statistics

View cache performance metrics.

**Endpoint:** `GET /api/cache/stats`

**Example:**
```bash
curl http://localhost:8080/api/cache/stats
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "hitCount": 1250,
    "missCount": 450,
    "hitRate": 0.735,
    "size": 890
  }
}
```

### 14. Clear Cache

Clear all cached word lookups.

**Endpoint:** `POST /api/cache/clear`

**Example:**
```bash
curl -X POST http://localhost:8080/api/cache/clear
```

**Response:**
```json
{
  "status": "ok",
  "data": "Cache cleared successfully"
}
```

---

## Health Check

### 15. Health Check

Check if the API is running.

**Endpoint:** `GET /api/health`

**Example:**
```bash
curl http://localhost:8080/api/health
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "status": "ok",
    "version": "1.0.0"
  }
}
```

**Also supports:** `HEAD /api/health` for monitoring services.

---

## Error Responses

All endpoints use a consistent error response format:

```json
{
  "status": "error",
  "message": "Error description here",
  "data": null
}
```

**Common HTTP Status Codes:**
- `200 OK`: Request successful
- `201 Created`: Resource created successfully
- `400 Bad Request`: Invalid request parameters
- `401 Unauthorized`: Missing or invalid authentication
- `403 Forbidden`: Insufficient permissions
- `404 Not Found`: Resource not found
- `500 Internal Server Error`: Server error

---

## Complete Endpoint Summary

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/words/search` | ❌ | Search word definitions |
| GET | `/api/words/details` | ❌ | Enhanced word details |
| GET | `/api/words/saved` | 🔒 | Get saved words |
| POST | `/api/words/saved` | 🔒 | Save a word |
| DELETE | `/api/words/saved/{word}` | 🔒 | Delete saved word |
| GET | `/api/flashcards` | 🔒 | Get all flashcards |
| POST | `/api/flashcards` | 🔒 | Create flashcard |
| PUT | `/api/flashcards/{id}` | 🔒 | Update flashcard progress |
| GET | `/api/flashcards/due` | 🔒 | Get due flashcards |
| GET | `/api/flashcards/statistics` | 🔒 | Get statistics |
| POST | `/api/flashcards/create` | 🔒 | Create from saved word |
| DELETE | `/api/flashcards/{id}` | 🔒 | Delete flashcard |
| GET | `/api/cache/stats` | ❌ | Cache statistics |
| POST | `/api/cache/clear` | ❌ | Clear cache |
| GET | `/api/health` | ❌ | Health check |

---

## Workflow Examples

### Example 1: Search and Save Word

```bash
# 1. Search for a word
curl "http://localhost:8080/api/words/details?query=serendipity"

# 2. Save it
curl -X POST http://localhost:8080/api/words/saved \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "word": "serendipity",
    "translation": "счастливая случайность",
    "definition": "The occurrence of events by chance in a happy way"
  }'
```

### Example 2: Create and Study Flashcards

```bash
# 1. Create a flashcard
curl -X POST http://localhost:8080/api/flashcards \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "word": "hello",
    "translation": "привет",
    "definition": "A greeting"
  }'

# 2. Get due cards
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/flashcards/due

# 3. Review the card
curl -X PUT http://localhost:8080/api/flashcards/1 \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"difficulty": "GOOD"}'

# 4. Check progress
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/flashcards/statistics
```

### Example 3: From Saved Word to Flashcard

```bash
# 1. Save a word (get ID from response)
curl -X POST http://localhost:8080/api/words/saved \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"word": "ephemeral", "translation": "эфемерный"}'

# Response: {"status": "ok", "data": {"id": 123, ...}}

# 2. Create flashcard from saved word
curl -X POST http://localhost:8080/api/flashcards/create \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"savedWordId": 123}'
```
