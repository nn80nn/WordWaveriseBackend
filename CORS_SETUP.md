# ✅ CORS Настроен Успешно

## Проверка конфигурации

**Дата:** 2025-12-06

### Результаты тестирования CORS

#### ✅ Preflight Request (OPTIONS)
```
HTTP/1.1 200 OK
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Credentials: true
Access-Control-Allow-Methods: DELETE, OPTIONS, PATCH, PUT
Access-Control-Allow-Headers: Authorization, Content-Type, MyCustomHeader
Access-Control-Max-Age: 86400
```

#### ✅ Actual Request (GET)
```
HTTP/1.1 200 OK
Vary: Origin
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Credentials: true
```

---

## Что настроено

### Разрешенные источники (Origins):
- ✅ `http://localhost:5173` (Vite/React dev server)
- ✅ `http://127.0.0.1:5173`

### Разрешенные HTTP методы:
- GET
- POST
- PUT
- DELETE
- PATCH
- OPTIONS
- HEAD

### Разрешенные заголовки:
- Authorization (для JWT токенов)
- Content-Type
- Accept
- MyCustomHeader

### Дополнительно:
- ✅ **Credentials enabled** - можно отправлять cookies и Authorization headers
- ✅ **Max-Age: 86400** (24 часа) - браузер кеширует preflight запросы

---

## Использование с фронтенда

### Axios пример

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080',
  withCredentials: true, // Важно!
});

// Добавить токен к запросам
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('authToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Использование
const response = await api.get('/api/health');
console.log(response.data);
```

### Fetch API пример

```javascript
fetch('http://localhost:8080/api/health', {
  method: 'GET',
  credentials: 'include', // Важно!
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  },
})
  .then(response => response.json())
  .then(data => console.log(data));
```

---

## Добавление нового origin для production

Когда вы деплоите фронтенд, добавьте production домен в `HTTP.kt`:

```kotlin
// Production frontend
allowHost("yourdomain.com", schemes = listOf("https"))
allowHost("www.yourdomain.com", schemes = listOf("https"))
```

**Важно:**
- Используйте HTTPS для production
- Не используйте `anyHost()` в production!

---

## Проверка CORS в браузере

### Chrome DevTools
1. Откройте DevTools (F12)
2. Перейдите в Network tab
3. Выполните запрос к API
4. Проверьте заголовки ответа:
   - `Access-Control-Allow-Origin: http://localhost:5173` ✓
   - `Access-Control-Allow-Credentials: true` ✓

### Console тест
```javascript
fetch('http://localhost:8080/api/health')
  .then(r => r.json())
  .then(data => console.log('✅ CORS works!', data))
  .catch(err => console.error('❌ CORS error:', err));
```

---

## Решение проблем

### Ошибка: "CORS policy: No 'Access-Control-Allow-Origin' header"

**Причина:** Origin не разрешен

**Решение:** Добавьте ваш origin в `HTTP.kt`:
```kotlin
allowHost("your-origin:port", schemes = listOf("http"))
```

### Ошибка: "CORS policy: Credentials flag is true, but Access-Control-Allow-Credentials is not"

**Причина:** `allowCredentials = true` не установлен

**Решение:** Убедитесь что в `HTTP.kt` есть:
```kotlin
allowCredentials = true
```

### Ошибка: "Preflight request doesn't pass access control check"

**Причина:** Недостающий метод или заголовок

**Решение:** Добавьте необходимый метод/заголовок в `HTTP.kt`:
```kotlin
allowMethod(HttpMethod.YourMethod)
allowHeader("YourHeader")
```

---

## Файлы конфигурации

- **CORS:** `src/main/kotlin/n/startapp/HTTP.kt`
- **Документация API:** `API.md`
- **Интеграция фронтенда:** `FRONTEND_INTEGRATION.md`

---

## Статус

🟢 **CORS полностью настроен и протестирован**

Фронтенд на `http://localhost:5173` может безопасно делать запросы к API!
