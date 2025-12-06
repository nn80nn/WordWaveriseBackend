# Интеграция фронтенда с WordWaverise Backend API

## Настройка CORS

Backend настроен для работы с фронтендом на **`http://localhost:5173`** (стандартный порт Vite/React dev server).

### Разрешенные настройки:

- **Методы:** GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD
- **Заголовки:** Authorization, Content-Type, Accept
- **Credentials:** Разрешены (можно отправлять cookies и Authorization headers)

---

## Примеры использования API с фронтенда

### 1. Базовая настройка Axios

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // Важно для отправки JWT токенов
});

// Интерсептор для добавления JWT токена ко всем запросам
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('authToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Интерсептор для обработки ошибок
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Пользователь не авторизован - перенаправить на страницу входа
      localStorage.removeItem('authToken');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default api;
```

### 2. Примеры запросов

#### Регистрация
```javascript
async function register(email, password) {
  try {
    const response = await api.post('/api/auth/register', {
      email,
      password,
    });

    // Сохранить токен
    localStorage.setItem('authToken', response.data.data.token);

    return response.data.data.user;
  } catch (error) {
    console.error('Registration failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Вход
```javascript
async function login(email, password) {
  try {
    const response = await api.post('/api/auth/login', {
      email,
      password,
    });

    localStorage.setItem('authToken', response.data.data.token);

    return response.data.data.user;
  } catch (error) {
    console.error('Login failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Поиск слова
```javascript
async function searchWord(query) {
  try {
    const response = await api.get('/api/words/details', {
      params: { query },
    });

    return response.data.data;
  } catch (error) {
    console.error('Search failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Сохранить слово (требует авторизации)
```javascript
async function saveWord(word, translation, definition) {
  try {
    const response = await api.post('/api/words/saved', {
      word,
      translation,
      definition,
    });

    return response.data.data;
  } catch (error) {
    console.error('Save failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Получить все сохраненные слова
```javascript
async function getSavedWords() {
  try {
    const response = await api.get('/api/words/saved');
    return response.data.data.words;
  } catch (error) {
    console.error('Fetch failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Создать флешкарту
```javascript
async function createFlashcard(word, translation, definition, example) {
  try {
    const response = await api.post('/api/flashcards', {
      word,
      translation,
      definition,
      example,
    });

    return response.data.data;
  } catch (error) {
    console.error('Create flashcard failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Получить флешкарты для повторения
```javascript
async function getDueFlashcards() {
  try {
    const response = await api.get('/api/flashcards/due');
    return response.data.data;
  } catch (error) {
    console.error('Fetch due cards failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Обзор флешкарты
```javascript
async function reviewFlashcard(cardId, difficulty) {
  // difficulty: 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'
  try {
    const response = await api.put(`/api/flashcards/${cardId}`, {
      difficulty,
    });

    return response.data.data;
  } catch (error) {
    console.error('Review failed:', error.response?.data?.message);
    throw error;
  }
}
```

#### Получить статистику флешкарт
```javascript
async function getFlashcardStats() {
  try {
    const response = await api.get('/api/flashcards/statistics');
    return response.data.data;
  } catch (error) {
    console.error('Fetch stats failed:', error.response?.data?.message);
    throw error;
  }
}
```

---

## Формат ответов

Все эндпоинты возвращают данные в едином формате:

### Успешный ответ:
```json
{
  "status": "ok",
  "data": { ... }
}
```

### Ответ с ошибкой:
```json
{
  "status": "error",
  "message": "Описание ошибки",
  "data": null
}
```

---

## Обработка ошибок

### Коды статусов HTTP:

| Код | Описание | Действие |
|-----|----------|----------|
| 200 | OK | Запрос успешен |
| 201 | Created | Ресурс создан (регистрация, создание флешкарты) |
| 400 | Bad Request | Некорректные данные - показать сообщение пользователю |
| 401 | Unauthorized | Не авторизован - перенаправить на страницу входа |
| 403 | Forbidden | Доступ запрещен |
| 404 | Not Found | Ресурс не найден |
| 500 | Internal Server Error | Ошибка сервера - показать общее сообщение об ошибке |

### Пример обработки:

```javascript
try {
  const data = await someApiCall();
  // Успех
} catch (error) {
  if (error.response) {
    // Сервер ответил с кодом ошибки
    const { status, data } = error.response;

    switch (status) {
      case 400:
        alert(`Ошибка: ${data.message}`);
        break;
      case 401:
        alert('Необходимо войти в систему');
        window.location.href = '/login';
        break;
      case 404:
        alert('Ресурс не найден');
        break;
      default:
        alert('Произошла ошибка. Попробуйте позже.');
    }
  } else if (error.request) {
    // Запрос был отправлен, но ответа не получено
    alert('Сервер не отвечает. Проверьте подключение.');
  } else {
    // Ошибка при настройке запроса
    console.error('Error:', error.message);
  }
}
```

---

## Проверка здоровья сервера

Используйте health check эндпоинт для проверки доступности API:

```javascript
async function checkServerHealth() {
  try {
    const response = await axios.get('http://localhost:8080/api/health');
    return response.data.data.status === 'ok';
  } catch (error) {
    return false;
  }
}

// Использование
if (await checkServerHealth()) {
  console.log('✅ Backend is ready');
} else {
  console.error('❌ Backend is unavailable');
}
```

---

## React Hook пример

```javascript
import { useState, useEffect } from 'react';
import api from './api';

function useAuth() {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Проверить, есть ли сохраненный токен
    const token = localStorage.getItem('authToken');
    if (token) {
      // Можно добавить проверку валидности токена
      // Для этого нужен эндпоинт GET /api/auth/me
    }
    setLoading(false);
  }, []);

  const login = async (email, password) => {
    const response = await api.post('/api/auth/login', { email, password });
    localStorage.setItem('authToken', response.data.data.token);
    setUser(response.data.data.user);
    return response.data.data.user;
  };

  const register = async (email, password) => {
    const response = await api.post('/api/auth/register', { email, password });
    localStorage.setItem('authToken', response.data.data.token);
    setUser(response.data.data.user);
    return response.data.data.user;
  };

  const logout = () => {
    localStorage.removeItem('authToken');
    setUser(null);
  };

  return { user, loading, login, register, logout };
}

export default useAuth;
```

---

## TypeScript типы

```typescript
// API Response
interface ApiResponse<T> {
  status: 'ok' | 'error';
  data: T | null;
  message?: string;
}

// User
interface User {
  id: number;
  email: string;
  createdAt: string;
}

// Auth Response
interface AuthResponse {
  token: string;
  user: User;
}

// Word Details
interface WordDetails {
  word: string;
  phonetic?: string;
  audioUrl?: string;
  translation?: string;
  definitions: Definition[];
  synonyms: string[];
  antonyms: string[];
  examples: string[];
}

interface Definition {
  partOfSpeech: string;
  definition: string;
  example?: string;
  source?: string;
}

// Saved Word
interface SavedWord {
  id: number;
  word: string;
  translation?: string;
  definition?: string;
  savedAt: string;
}

// Flashcard
interface Flashcard {
  id: number;
  userId: number;
  savedWordId?: number;
  word: string;
  translation: string;
  definition?: string;
  example?: string;
  easeFactor: number;
  repetitions: number;
  interval: number;
  nextReview: string;
  lastReviewed?: string;
  createdAt: string;
  updatedAt: string;
}

// Review Difficulty
type ReviewDifficulty = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY';

// Flashcard Statistics
interface FlashcardStatistics {
  totalCards: number;
  dueCards: number;
  learnedCards: number;
  newCards: number;
  reviewingCards: number;
}
```

---

## Решение распространенных проблем

### CORS ошибки

Если вы видите ошибку:
```
Access to XMLHttpRequest at 'http://localhost:8080/...' from origin 'http://localhost:5173'
has been blocked by CORS policy
```

**Решение:**
1. Убедитесь, что backend запущен
2. Проверьте, что вы используете правильный порт фронтенда (5173)
3. Если используете другой порт, обновите `HTTP.kt` на бэкенде:
   ```kotlin
   allowHost("localhost:YOUR_PORT", schemes = listOf("http"))
   ```

### 401 Unauthorized на защищенных эндпоинтах

**Проблема:** JWT токен не отправляется или недействителен

**Решение:**
1. Проверьте, что токен сохранен: `localStorage.getItem('authToken')`
2. Убедитесь, что интерсептор Axios добавляет заголовок `Authorization: Bearer TOKEN`
3. Проверьте формат заголовка (должен быть `Bearer` + пробел + токен)

### Сервер не отвечает

**Проблема:** `Network Error` или timeout

**Решение:**
1. Проверьте, что backend запущен: `./gradlew run`
2. Проверьте порт: backend должен быть на `http://localhost:8080`
3. Проверьте firewall/антивирус

---

## Полезные ссылки

- [Полная документация API](API.md)
- [Отчет о тестировании](TEST_REPORT.md)
- Backend URL: `http://localhost:8080`
- Frontend URL (по умолчанию): `http://localhost:5173`
