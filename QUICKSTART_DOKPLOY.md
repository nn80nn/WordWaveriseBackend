# 🚀 Quick Start: Развертывание с Dokploy

Быстрое руководство по развертыванию WordWaveriseBackend на сервере с помощью Dokploy за 15 минут.

## Шаг 1: Подготовка сервера (5 мин)

```bash
# Подключитесь к вашему серверу
ssh root@your-server-ip

# Установите Dokploy одной командой
curl -sSL https://dokploy.com/install.sh | sh

# Дождитесь завершения установки
# Вы получите URL: http://your-server-ip:3000
```

## Шаг 2: Настройка Dokploy (3 мин)

1. Откройте в браузере: `http://your-server-ip:3000`
2. Войдите с предоставленными учетными данными
3. **Смените пароль** в Settings → Account

## Шаг 3: Загрузка кода на GitHub (2 мин)

```bash
# В вашей локальной директории проекта
cd C:\Users\80n\Documents\WordWaveriseBackend

# Добавьте все файлы
git add .

# Создайте коммит
git commit -m "Add Dokploy deployment configuration"

# Отправьте на GitHub
git push origin master
```

## Шаг 4: Создание приложения в Dokploy (5 мин)

### 4.1 Создание проекта

1. В Dokploy нажмите **"+ Create Project"**
2. Название: `wordwaverise`
3. Нажмите **"Create"**

### 4.2 Добавление приложения

1. Нажмите **"+ Add Application"**
2. Выберите **"Docker Compose"**
3. Заполните:
   - **Name**: `wordwaverise-backend`
   - **Source**: Git Repository
   - **Repository URL**: `https://github.com/YOUR_USERNAME/WordWaveriseBackend`
   - **Branch**: `master`
   - **Compose File**: `docker-compose.yml`

### 4.3 Настройка переменных окружения

Перейдите в **Environment Variables** и добавьте:

```env
POSTGRES_DB=wordwaverise
POSTGRES_USER=wordwaverise_user
POSTGRES_PASSWORD=YOUR_STRONG_PASSWORD
JWT_SECRET=YOUR_256_BIT_SECRET
JWT_ISSUER=wordwaverise-backend
JWT_AUDIENCE=wordwaverise-app
JWT_REALM=Access to WordWaverise API
JWT_EXPIRATION_HOURS=720
```

**Генерация паролей:**

```bash
# На вашем компьютере или сервере:
openssl rand -base64 32  # Для POSTGRES_PASSWORD
openssl rand -base64 32  # Для JWT_SECRET
```

### 4.4 Запуск

Нажмите **"Deploy"** и дождитесь завершения (2-5 минут).

## Шаг 5: Проверка (1 мин)

```bash
# Проверьте API
curl http://your-server-ip:8080/api/health

# Ожидаемый ответ:
{
  "status": "ok",
  "data": {
    "status": "ok",
    "version": "1.0.0"
  }
}
```

---

## Дополнительно: Настройка домена (опционально)

### 1. Настройте DNS

Создайте A-запись:
```
api.yourdomain.com → your-server-ip
```

### 2. В Dokploy

1. Перейдите в **Domains**
2. Нажмите **"+ Add Domain"**
3. Введите: `api.yourdomain.com`
4. Порт: `8080`
5. Включите **SSL/TLS**
6. Нажмите **"Save"**

Dokploy автоматически выпустит SSL сертификат (Let's Encrypt).

### 3. Проверка

```bash
curl https://api.yourdomain.com/api/health
```

---

## Автоматический деплой при push

### Вариант 1: GitHub Webhook

1. В Dokploy → Application → Settings → найдите **Deploy Webhook URL**
2. Скопируйте URL
3. В GitHub → Settings → Webhooks → Add webhook:
   - **Payload URL**: [URL из Dokploy]
   - **Content type**: `application/json`
   - **Events**: Just the push event
4. Сохраните

Теперь каждый `git push` автоматически деплоит приложение!

---

## Управление приложением

### Через Dokploy UI

- **Логи**: Application → Logs
- **Перезапуск**: Application → Restart
- **Остановка**: Application → Stop
- **Переменные**: Application → Environment

### Через SSH

```bash
ssh root@your-server-ip

# Переход в директорию
cd /opt/dokploy/projects/wordwaverise/wordwaverise-backend

# Просмотр логов
docker-compose logs -f app

# Перезапуск
docker-compose restart app

# Обновление
docker-compose pull
docker-compose up -d
```

---

## Troubleshooting

### Приложение не запускается

```bash
# Проверьте логи
docker-compose logs app

# Перезапуск
docker-compose restart app
```

### Ошибка подключения к БД

```bash
# Проверьте статус БД
docker-compose ps db

# Логи БД
docker-compose logs db
```

### Нет доступа к приложению

```bash
# Проверьте порты
sudo ufw allow 8080/tcp

# Или откройте все необходимые порты
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 3000/tcp
```

---

## Полная документация

Для подробной информации смотрите:
- **DOKPLOY_DEPLOYMENT.md** - Полное руководство по развертыванию
- **DEPLOYMENT.md** - Альтернативные методы развертывания
- **CLAUDE.md** - Архитектура проекта

---

## Поддержка

- [Dokploy Documentation](https://docs.dokploy.com)
- [Dokploy Discord](https://discord.gg/dokploy)
- [GitHub Issues](https://github.com/YOUR_USERNAME/WordWaveriseBackend/issues)

---

**Готово! Ваше приложение работает! 🎉**

Следующие шаги:
- Настройте мониторинг (Uptime Robot, StatusCake)
- Настройте резервное копирование
- Добавьте домен и SSL
- Настройте firewall
