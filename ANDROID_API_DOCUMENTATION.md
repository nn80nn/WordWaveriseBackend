# WordWaverise Backend API - Android Client Documentation

## Base URL
```
Production: http://your-server-ip:8080
Local Testing: http://10.0.2.2:8080 (Android Emulator)
```

## Response Format

All API responses follow this structure:

```json
{
  "status": "ok" | "error",
  "data": <ResponseData> | null,
  "message": string | null
}
```

---

## Authentication Endpoints

### 1. Register New User

**Endpoint:** `POST /api/auth/register`

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Success Response (201):**
```json
{
  "status": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "email": "user@example.com",
      "createdAt": "2025-10-24T00:30:55.412744Z"
    }
  }
}
```

**Error Responses:**
- `400` - Invalid email format, password too short, or user already exists
- `500` - Internal server error

**Kotlin Data Classes:**
```kotlin
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDTO
)

@Serializable
data class UserDTO(
    val id: Int,
    val email: String,
    val createdAt: String
)
```

---

### 2. Login

**Endpoint:** `POST /api/auth/login`

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Success Response (200):**
```json
{
  "status": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "email": "user@example.com",
      "createdAt": "2025-10-24T00:30:55.412744Z"
    }
  }
}
```

**Error Responses:**
- `401` - Invalid email or password
- `400` - Missing email or password

**Kotlin Data Classes:**
```kotlin
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)
```

---

## Word Search Endpoint (Public)

### 3. Search Word

**Endpoint:** `GET /api/words/search?query={word}`

**Headers:** None required

**Query Parameters:**
- `query` (required) - The word to search for

**Success Response (200):**
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
        "synonyms": ["hi", "hey"],
        "antonyms": ["bye", "goodbye"]
      }
    ]
  }
}
```

**Error Responses:**
- `404` - Word not found in dictionary
- `400` - Query parameter missing

**Kotlin Data Classes:**
```kotlin
@Serializable
data class WordSearchResponse(
    val word: String,
    val phonetic: String?,
    val audioUrl: String?,
    val translation: String?,
    val definitions: List<Definition>
)

@Serializable
data class Definition(
    val partOfSpeech: String,
    val definition: String,
    val example: String?,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList()
)
```

---

## Saved Words Endpoints (Protected)

**Note:** All saved words endpoints require JWT authentication.

**Authentication Header:**
```
Authorization: Bearer {your_jwt_token}
```

---

### 4. Save a Word

**Endpoint:** `POST /api/words/save`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {token}
```

**Request Body:**
```json
{
  "word": "hello"
}
```

**Success Response (200):**
```json
{
  "status": "ok",
  "data": {
    "id": 1,
    "word": "hello",
    "savedAt": "2025-10-24T00:31:39.008402Z"
  }
}
```

**Error Responses:**
- `401` - Invalid or missing token
- `400` - Word parameter missing or empty

**Kotlin Data Classes:**
```kotlin
@Serializable
data class SaveWordRequest(
    val word: String
)

@Serializable
data class SavedWordDTO(
    val id: Int,
    val word: String,
    val savedAt: String
)
```

---

### 5. Get All Saved Words

**Endpoint:** `GET /api/words/saved`

**Headers:**
```
Authorization: Bearer {token}
```

**Success Response (200):**
```json
{
  "status": "ok",
  "data": {
    "words": [
      {
        "id": 2,
        "word": "world",
        "savedAt": "2025-10-24T00:31:40.974633Z"
      },
      {
        "id": 1,
        "word": "hello",
        "savedAt": "2025-10-24T00:31:39.008402Z"
      }
    ]
  }
}
```

**Error Responses:**
- `401` - Invalid or missing token

**Kotlin Data Classes:**
```kotlin
@Serializable
data class SavedWordsResponse(
    val words: List<SavedWordDTO>
)
```

---

### 6. Delete a Saved Word

**Endpoint:** `DELETE /api/words/saved/{word}`

**Headers:**
```
Authorization: Bearer {token}
```

**Path Parameters:**
- `word` - The word to delete (URL encoded)

**Success Response (200):**
```
HTTP 200 OK
```

**Error Responses:**
- `401` - Invalid or missing token
- `404` - Word not found in saved words
- `400` - Word parameter missing

---

## System Endpoints

### 7. Health Check

**Endpoint:** `GET /api/health`

**Headers:** None required

**Success Response (200):**
```json
{
  "status": "ok",
  "data": {
    "status": "ok",
    "version": "1.0.0"
  }
}
```

**Kotlin Data Classes:**
```kotlin
@Serializable
data class HealthStatus(
    val status: String,
    val version: String
)
```

---

## Complete Kotlin Models for Android

```kotlin
package com.yourapp.data.models

import kotlinx.serialization.Serializable

// Generic API Response Wrapper
@Serializable
data class ApiResponse<T>(
    val status: String,
    val data: T? = null,
    val message: String? = null
)

// Auth Models
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDTO
)

@Serializable
data class UserDTO(
    val id: Int,
    val email: String,
    val createdAt: String
)

// Word Search Models
@Serializable
data class WordSearchResponse(
    val word: String,
    val phonetic: String?,
    val audioUrl: String?,
    val translation: String?,
    val definitions: List<Definition>
)

@Serializable
data class Definition(
    val partOfSpeech: String,
    val definition: String,
    val example: String?,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList()
)

// Saved Words Models
@Serializable
data class SaveWordRequest(
    val word: String
)

@Serializable
data class SavedWordDTO(
    val id: Int,
    val word: String,
    val savedAt: String
)

@Serializable
data class SavedWordsResponse(
    val words: List<SavedWordDTO>
)

// Health Check Model
@Serializable
data class HealthStatus(
    val status: String,
    val version: String
)
```

---

## Retrofit Service Interface Example

```kotlin
package com.yourapp.data.api

import com.yourapp.data.models.*
import retrofit2.Response
import retrofit2.http.*

interface WordWaveriseApi {

    // Health Check
    @GET("api/health")
    suspend fun healthCheck(): Response<ApiResponse<HealthStatus>>

    // Authentication
    @POST("api/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<ApiResponse<AuthResponse>>

    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ApiResponse<AuthResponse>>

    // Word Search (Public)
    @GET("api/words/search")
    suspend fun searchWord(
        @Query("query") query: String
    ): Response<ApiResponse<WordSearchResponse>>

    // Saved Words (Protected)
    @POST("api/words/save")
    suspend fun saveWord(
        @Header("Authorization") token: String,
        @Body request: SaveWordRequest
    ): Response<ApiResponse<SavedWordDTO>>

    @GET("api/words/saved")
    suspend fun getSavedWords(
        @Header("Authorization") token: String
    ): Response<ApiResponse<SavedWordsResponse>>

    @DELETE("api/words/saved/{word}")
    suspend fun deleteSavedWord(
        @Header("Authorization") token: String,
        @Path("word") word: String
    ): Response<Unit>
}
```

---

## Error Handling

All error responses follow this format:

```json
{
  "status": "error",
  "message": "Error description"
}
```

**Common HTTP Status Codes:**
- `200` - Success
- `201` - Created (for registration)
- `400` - Bad Request (validation errors)
- `401` - Unauthorized (invalid credentials or token)
- `403` - Forbidden
- `404` - Not Found
- `500` - Internal Server Error

---

## Authentication Flow

1. **Register** or **Login** to get JWT token
2. Store the token securely (e.g., using EncryptedSharedPreferences)
3. Include token in Authorization header for protected endpoints:
   ```
   Authorization: Bearer {your_token}
   ```
4. Token expires after 720 hours (30 days)
5. If token expires, user needs to login again

---

## Usage Examples

### Example: Complete Authentication Flow

```kotlin
// 1. Register
val registerRequest = RegisterRequest(
    email = "user@example.com",
    password = "password123"
)
val registerResponse = api.register(registerRequest)

if (registerResponse.isSuccessful) {
    val authData = registerResponse.body()?.data
    val token = authData?.token
    val user = authData?.user

    // Save token securely
    tokenManager.saveToken(token)
}

// 2. Use token for protected endpoints
val token = "Bearer ${tokenManager.getToken()}"

// Save a word
val saveResponse = api.saveWord(
    token = token,
    request = SaveWordRequest(word = "hello")
)

// Get saved words
val savedWordsResponse = api.getSavedWords(token = token)

// Delete a word
val deleteResponse = api.deleteSavedWord(
    token = token,
    word = "hello"
)
```

---

## Notes for Android Development

1. **Use HTTPS in production** - Update base URL to use `https://` when deploying
2. **Handle network errors** - Implement proper error handling for network failures
3. **Cache tokens** - Use secure storage for JWT tokens
4. **Offline support** - Consider implementing local database for saved words
5. **Request timeouts** - Configure appropriate timeouts in OkHttp client
6. **Interceptors** - Add interceptor to automatically include auth token:

```kotlin
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenManager.getToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
```

---

## Testing Endpoints

Use the provided curl commands or Postman collection to test endpoints before implementing in Android app.

For local testing on Android Emulator, use:
- `http://10.0.2.2:8080` instead of `http://localhost:8080`

For testing on physical device:
- Use your computer's IP address: `http://192.168.x.x:8080`
- Ensure device and computer are on the same network
