# Flashcard System with Spaced Repetition

## Overview

The WordWaveriseBackend now includes a complete flashcard system with intelligent spaced repetition using the **SM-2 (SuperMemo 2) algorithm**. This scientifically-proven algorithm optimizes learning by scheduling reviews at optimal intervals based on your performance.

## Features

- **Spaced Repetition Learning**: Uses SM-2 algorithm for optimal review scheduling
- **Performance-Based Intervals**: Review frequency adapts to how well you know each word
- **Progress Tracking**: Track learned, reviewing, and new cards
- **JWT Authentication**: All endpoints are protected and user-specific
- **Database Integration**: Flashcards linked to saved words

## SM-2 Algorithm

The system uses the SuperMemo 2 algorithm:

### How It Works

1. **Initial Review**: New cards start with a 1-day interval
2. **Performance Rating**: After each review, you rate how well you remembered:
   - `AGAIN` (0): Forgot completely → Reset to 1 day
   - `HARD` (3): Difficult recall → Shorter interval
   - `GOOD` (4): Good recall → Standard interval
   - `EASY` (5): Perfect recall → Longer interval

3. **Adaptive Intervals**:
   - 1st review: 1 day
   - 2nd review: 6 days
   - 3rd+ reviews: Previous interval × ease factor

4. **Ease Factor**: Adjusts based on performance (1.3 to 2.5+)
   - Better performance → longer intervals
   - Worse performance → shorter intervals

## API Endpoints

All endpoints require JWT authentication via `Authorization: Bearer {token}` header.

### 1. Get Due Flashcards

Get all flashcards that are due for review.

```http
GET /api/flashcards/due
Authorization: Bearer {token}
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "cards": [
      {
        "id": 1,
        "word": "serendipity",
        "translation": "счастливая случайность",
        "definition": "The occurrence of events by chance...",
        "example": "Finding that book was pure serendipity",
        "nextReview": "2025-01-24T10:00:00Z",
        "daysUntilReview": 0
      }
    ],
    "totalDue": 1
  }
}
```

### 2. Review a Flashcard

Submit your review and get the next review schedule.

```http
POST /api/flashcards/review
Authorization: Bearer {token}
Content-Type: application/json

{
  "cardId": 1,
  "difficulty": "GOOD"
}
```

**Difficulty Options:**
- `AGAIN`: Forgot completely
- `HARD`: Difficult to recall
- `GOOD`: Good recall (most common)
- `EASY`: Perfect recall

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

### 3. Create Flashcard from Saved Word

Convert a saved word into a flashcard for spaced repetition learning.

```http
POST /api/flashcards/create
Authorization: Bearer {token}
Content-Type: application/json

{
  "savedWordId": 123
}
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

### 4. Get All Flashcards

Retrieve all flashcards for the authenticated user.

```http
GET /api/flashcards
Authorization: Bearer {token}
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
      "example": null,
      "nextReview": "2025-01-30T10:00:00Z",
      "daysUntilReview": 6
    }
  ]
}
```

### 5. Get Flashcard Statistics

View your learning progress statistics.

```http
GET /api/flashcards/statistics
Authorization: Bearer {token}
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

### 6. Delete a Flashcard

Remove a flashcard from your deck.

```http
DELETE /api/flashcards/{id}
Authorization: Bearer {token}
```

**Response:**
```json
{
  "status": "ok",
  "data": "Flashcard deleted successfully"
}
```

## Database Schema

### Flashcards Table

```sql
CREATE TABLE flashcards (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    saved_word_id INTEGER REFERENCES saved_words(id),
    word VARCHAR(100),
    translation VARCHAR(255),
    definition TEXT,
    example TEXT,

    -- SM-2 Algorithm fields
    ease_factor REAL DEFAULT 2.5,
    repetitions INTEGER DEFAULT 0,
    interval INTEGER DEFAULT 0,
    next_review TIMESTAMP,
    last_reviewed TIMESTAMP,

    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

### Updated SavedWords Table

The `saved_words` table now includes optional `translation` and `definition` fields:

```sql
CREATE TABLE saved_words (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    word VARCHAR(255),
    translation VARCHAR(500),  -- NEW
    definition TEXT,           -- NEW
    saved_at TIMESTAMP,
    UNIQUE(user_id, word)
);
```

## Usage Flow

### 1. Save Words with Context

```bash
curl -X POST http://localhost:8080/api/words/save \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "word": "serendipity",
    "translation": "счастливая случайность",
    "definition": "The occurrence of events by chance in a happy way"
  }'
```

### 2. Create Flashcards

```bash
curl -X POST http://localhost:8080/api/flashcards/create \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"savedWordId": 123}'
```

### 3. Study Daily

```bash
# Get cards due for review
curl -X GET http://localhost:8080/api/flashcards/due \
  -H "Authorization: Bearer {token}"

# Review each card
curl -X POST http://localhost:8080/api/flashcards/review \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "cardId": 1,
    "difficulty": "GOOD"
  }'
```

### 4. Track Progress

```bash
curl -X GET http://localhost:8080/api/flashcards/statistics \
  -H "Authorization: Bearer {token}"
```

## Review Intervals Example

Here's how intervals grow based on performance:

| Review # | Rating | New Interval | Cumulative Days |
|----------|--------|--------------|----------------|
| 1st | GOOD | 1 day | 1 |
| 2nd | GOOD | 6 days | 7 |
| 3rd | GOOD | 15 days | 22 |
| 4th | GOOD | 37 days | 59 |
| 5th | EASY | 115 days | 174 |

If you rate a card as `AGAIN`, it resets to 1 day and starts over.

## Best Practices

### For Users

1. **Review Daily**: Check `/api/flashcards/due` daily
2. **Be Honest**: Rate cards based on actual recall difficulty
3. **Use AGAIN Freely**: Don't hesitate to reset cards you forgot
4. **Start Small**: Begin with 5-10 cards per day
5. **Add Context**: Include translations and definitions when saving words

### For Developers

1. **Scheduled Notifications**: Build a cron job to notify users of due cards
2. **Batch Reviews**: Allow reviewing multiple cards in one session
3. **Statistics Dashboard**: Display progress charts using `/statistics` endpoint
4. **Export/Import**: Add backup functionality for flashcard decks
5. **Gamification**: Add streaks, achievements, and XP based on reviews

## Testing

### Create Test Data

```bash
# 1. Register and login
TOKEN="your_jwt_token_here"

# 2. Save some words
curl -X POST http://localhost:8080/api/words/save \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"word": "hello", "translation": "привет", "definition": "A greeting"}'

# 3. Get saved word ID
SAVED_WORDS=$(curl -X GET http://localhost:8080/api/words/saved \
  -H "Authorization: Bearer $TOKEN")

# 4. Create flashcard
curl -X POST http://localhost:8080/api/flashcards/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"savedWordId": 1}'

# 5. Review the card
curl -X POST http://localhost:8080/api/flashcards/review \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cardId": 1, "difficulty": "GOOD"}'
```

## Architecture

```
┌─────────────────┐
│ FlashcardRoutes │  (JWT Protected)
└────────┬────────┘
         │
         ▼
┌──────────────────┐
│ FlashcardService │
└────────┬─────────┘
         │
         ├─► FlashcardRepository
         │   └─► Database (Flashcards table)
         │
         └─► SpacedRepetitionAlgorithm
             └─► SM-2 calculations
```

## Future Enhancements

- [ ] Add audio pronunciation to flashcards
- [ ] Support for image-based flashcards
- [ ] Custom tags and categories
- [ ] Shared decks between users
- [ ] Import from Anki/Quizlet
- [ ] Detailed learning analytics
- [ ] Mobile app push notifications for due reviews
- [ ] Review history and performance graphs

## References

- [SuperMemo Algorithm SM-2](https://www.supermemo.com/en/archives1990-2015/english/ol/sm2)
- [Spaced Repetition Research](https://en.wikipedia.org/wiki/Spaced_repetition)
