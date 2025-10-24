# API Usage Examples

Base URL: `http://localhost:8080`

## Authentication Endpoints

### 1. Register a New User

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password123"}'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
    "user": {
      "id": 1,
      "email": "user@example.com",
      "createdAt": "2025-10-24T01:00:00Z"
    }
  }
}
```

### 2. Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type": application/json" \
  -d '{"email": "user@example.com", "password": "password123"}'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
    "user": {
      "id": 1,
      "email": "user@example.com",
      "createdAt": "2025-10-24T01:00:00Z"
    }
  }
}
```

## Word Search Endpoint (Public)

### Search for a Word

```bash
curl http://localhost:8080/api/words/search?query=hello
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "word": "hello",
    "phonetic": "/həˈləʊ/",
    "audioUrl": "https://api.dictionaryapi.dev/media/pronunciations/en/hello-au.mp3",
    "translation": "Алло",
    "definitions": [
      {
        "partOfSpeech": "interjection",
        "definition": "A greeting (salutation) said when meeting someone...",
        "example": "Hello, everyone.",
        "synonyms": [],
        "antonyms": []
      }
    ]
  }
}
```

## Saved Words Endpoints (Require Authentication)

**Note:** All saved words endpoints require JWT token in Authorization header.

### 1. Save a Word

```bash
curl -X POST http://localhost:8080/api/words/save \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"word": "hello"}'
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "success": true,
    "word": {
      "id": 1,
      "word": "hello",
      "savedAt": "2025-10-24T01:00:00Z"
    }
  }
}
```

### 2. Get All Saved Words

```bash
curl http://localhost:8080/api/words/saved \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "words": [
      {
        "id": 1,
        "word": "hello",
        "savedAt": "2025-10-24T01:00:00Z"
      },
      {
        "id": 2,
        "word": "world",
        "savedAt": "2025-10-24T01:05:00Z"
      }
    ]
  }
}
```

### 3. Delete a Saved Word

```bash
curl -X DELETE http://localhost:8080/api/words/saved/hello \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

**Response:**
```json
{
  "status": "ok",
  "data": {
    "success": true,
    "message": "Word removed from saved words"
  }
}
```

## Error Responses

### 400 Bad Request
```json
{
  "status": "error",
  "message": "Email and password are required"
}
```

### 401 Unauthorized
```json
{
  "status": "error",
  "message": "Invalid email or password"
}
```

### 404 Not Found
```json
{
  "status": "error",
  "message": "Word 'xyz' not found in dictionary"
}
```

## Complete Workflow Example

```bash
# 1. Register
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "test@test.com", "password": "test123"}' \
  | jq -r '.data.token')

# 2. Search for a word
curl -s http://localhost:8080/api/words/search?query=book | jq

# 3. Save the word
curl -s -X POST http://localhost:8080/api/words/save \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"word": "book"}' | jq

# 4. Get all saved words
curl -s http://localhost:8080/api/words/saved \
  -H "Authorization: Bearer $TOKEN" | jq

# 5. Delete a saved word
curl -s -X DELETE http://localhost:8080/api/words/saved/book \
  -H "Authorization: Bearer $TOKEN" | jq
```

## Health Check

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
