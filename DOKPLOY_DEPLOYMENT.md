# Развертывание WordWaveriseBackend с Dokploy

Полное руководство по развертыванию приложения на сервере с использованием Dokploy и CI/CD через GitHub Actions.

## 📋 Содержание

1. [Введение](#введение)
2. [Предварительные требования](#предварительные-требования)
3. [Установка Dokploy на сервер](#установка-dokploy-на-сервер)
4. [Подготовка проекта](#подготовка-проекта)
5. [Настройка приложения в Dokploy](#настройка-приложения-в-dokploy)
6. [Настройка CI/CD с GitHub Actions](#настройка-cicd-с-github-actions)
7. [Управление приложением](#управление-приложением)
8. [Мониторинг и логи](#мониторинг-и-логи)
9. [Troubleshooting](#troubleshooting)
10. [Лучшие практики](#лучшие-практики)

---

## Введение

Dokploy - это self-hosted платформа для развертывания приложений, аналог Vercel/Railway/Render. Преимущества:
- 🚀 Простое развертывание через Git
- 🐳 Поддержка Docker и Docker Compose
- 🔄 Автоматический CI/CD
- 📊 Встроенный мониторинг
- 🔒 SSL сертификаты (Let's Encrypt)
- 💾 Управление базами данных
- 🌐 Управление доменами

---

## Предварительные требования

### Сервер
- **VPS/Dedicated сервер** (рекомендуется минимум 2GB RAM, 2 CPU)
- **ОС**: Ubuntu 20.04+ / Debian 11+ / CentOS 8+
- **Порты**: 22 (SSH), 80 (HTTP), 443 (HTTPS), 3000 (Dokploy UI)
- **Доступ**: root или sudo права

### Локально
- Git установлен
- GitHub аккаунт
- Базовые знания Docker и Linux

### Доменное имя (опционально, но рекомендуется)
- Домен для API (например, `api.yourdomain.com`)
- A-запись, указывающая на IP сервера

---

## Установка Dokploy на сервер

### Шаг 1: Подключение к серверу

```bash
ssh root@your-server-ip
# или
ssh your-username@your-server-ip
```

### Шаг 2: Обновление системы

```bash
# Для Ubuntu/Debian
sudo apt update && sudo apt upgrade -y

# Для CentOS
sudo yum update -y
```

### Шаг 3: Установка Docker (если не установлен)

```bash
# Установка Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Добавление пользователя в группу docker (опционально)
sudo usermod -aG docker $USER

# Проверка установки
docker --version
docker compose version
```

### Шаг 4: Установка Dokploy

Dokploy устанавливается одной командой:

```bash
curl -sSL https://dokploy.com/install.sh | sh
```

**Альтернативный метод (если первый не работает):**

```bash
# Клонирование репозитория
git clone https://github.com/Dokploy/dokploy.git
cd dokploy

# Запуск установки
./install.sh
```

Установка займет 2-5 минут. После завершения вы увидите:
```
✅ Dokploy установлен успешно!
🌐 URL: http://your-server-ip:3000
📧 Email: admin@dokploy.com (измените после первого входа)
🔑 Пароль: [сгенерированный пароль]
```

### Шаг 5: Первый вход в Dokploy

1. Откройте браузер: `http://your-server-ip:3000`
2. Войдите с предоставленными учетными данными
3. **Обязательно смените пароль!**

### Шаг 6: Настройка SSL для Dokploy UI (опционально)

Если у вас есть домен (например, `dokploy.yourdomain.com`):

1. Создайте A-запись: `dokploy.yourdomain.com → server-ip`
2. В Dokploy UI → Settings → Domain
3. Добавьте домен и включите SSL

---

## Подготовка проекта

### Шаг 1: Коммит изменений в Git

```bash
cd C:\Users\80n\Documents\WordWaveriseBackend

# Добавить новые файлы
git add Dockerfile dokploy.yaml docker-compose.yml .dockerignore

# Создать коммит
git commit -m "Add Dokploy configuration and Dockerfile"

# Отправить на GitHub
git push origin master
```

### Шаг 2: Проверка файлов

Убедитесь, что в репозитории есть:
- ✅ `Dockerfile` - для сборки образа
- ✅ `docker-compose.yml` - для оркестрации сервисов
- ✅ `dokploy.yaml` - конфигурация Dokploy (опционально)
- ✅ `.dockerignore` - исключения при сборке
- ✅ `.github/workflows/ci.yml` - CI pipeline
- ✅ `.github/workflows/cd.yml` - CD pipeline

---

## Настройка приложения в Dokploy

### Метод 1: Через Dokploy UI (Рекомендуется)

#### 1. Создание нового проекта

1. Войдите в Dokploy: `http://your-server-ip:3000`
2. Нажмите **"+ Create Project"**
3. Введите название: `wordwaverise`
4. Нажмите **"Create"**

#### 2. Добавление приложения

1. В проекте нажмите **"+ Add Application"**
2. Выберите тип: **"Docker Compose"**
3. Заполните форму:
   - **Name**: `wordwaverise-backend`
   - **Source**: `Git Repository`
   - **Repository URL**: `https://github.com/YOUR_USERNAME/WordWaveriseBackend`
   - **Branch**: `master`
   - **Docker Compose File**: `docker-compose.yml`

#### 3. Настройка переменных окружения

В разделе **Environment Variables** добавьте:

```env
# Database
POSTGRES_DB=wordwaverise
POSTGRES_USER=wordwaverise_user
POSTGRES_PASSWORD=YOUR_STRONG_PASSWORD_HERE

# JWT Configuration
JWT_SECRET=YOUR_256_BIT_RANDOM_STRING_HERE
JWT_ISSUER=wordwaverise-backend
JWT_AUDIENCE=wordwaverise-app
JWT_REALM=Access to WordWaverise API
JWT_EXPIRATION_HOURS=720
```

**Генерация безопасных паролей:**

```bash
# Генерация POSTGRES_PASSWORD
openssl rand -base64 32

# Генерация JWT_SECRET (256 бит = 32 байта)
openssl rand -base64 32
```

#### 4. Настройка домена (опционально)

1. Перейдите в **Domains**
2. Нажмите **"+ Add Domain"**
3. Введите домен: `api.yourdomain.com`
4. Порт: `8080`
5. Включите **SSL/TLS** (Let's Encrypt)
6. Включите **Auto-redirect HTTP → HTTPS**

#### 5. Запуск приложения

1. Нажмите **"Deploy"**
2. Dokploy автоматически:
   - Клонирует репозиторий
   - Соберет Docker образ
   - Запустит контейнеры
   - Настроит сеть и volumes
   - Выпустит SSL сертификат (если домен настроен)

#### 6. Проверка развертывания

Статус можно отслеживать в разделе **Deployments**. После завершения:

```bash
# Проверка через IP
curl http://your-server-ip:8080/api/health

# Проверка через домен
curl https://api.yourdomain.com/api/health

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

### Метод 2: Через Dokploy CLI (Продвинутый)

```bash
# Установка Dokploy CLI
npm install -g @dokploy/cli

# Авторизация
dokploy login http://your-server-ip:3000

# Создание проекта
dokploy project create wordwaverise

# Развертывание приложения
dokploy app deploy \
  --name wordwaverise-backend \
  --project wordwaverise \
  --git https://github.com/YOUR_USERNAME/WordWaveriseBackend \
  --branch master \
  --compose docker-compose.yml
```

---

## Настройка CI/CD с GitHub Actions

Проект уже имеет GitHub Actions workflows, но для полной автоматизации нужно настроить автоматический деплой в Dokploy.

### Вариант 1: Webhook от GitHub → Dokploy

#### 1. Получение Webhook URL из Dokploy

1. В Dokploy → Application → Settings
2. Найдите **Deploy Webhook**
3. Скопируйте URL (например: `http://your-server-ip:3000/api/deploy/webhook/abc123`)

#### 2. Добавление Webhook в GitHub

1. Откройте репозиторий на GitHub
2. Settings → Webhooks → Add webhook
3. Заполните:
   - **Payload URL**: `[URL из Dokploy]`
   - **Content type**: `application/json`
   - **Secret**: (создайте и добавьте в Dokploy)
   - **Events**: `Just the push event`
4. Сохраните

Теперь каждый push в `master` автоматически вызовет деплой в Dokploy!

---

### Вариант 2: GitHub Actions → Dokploy API

Обновите `.github/workflows/cd.yml`:

```yaml
# Добавьте в конец файла .github/workflows/cd.yml
  deploy-to-dokploy:
    name: Deploy to Dokploy
    needs: build-and-deploy
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/master'

    steps:
    - name: Trigger Dokploy Deployment
      run: |
        curl -X POST "${{ secrets.DOKPLOY_WEBHOOK_URL }}" \
          -H "Content-Type: application/json" \
          -H "X-Hub-Signature: ${{ secrets.DOKPLOY_WEBHOOK_SECRET }}" \
          -d '{
            "ref": "refs/heads/master",
            "repository": {
              "full_name": "${{ github.repository }}"
            }
          }'
```

Добавьте секреты в GitHub:
1. Settings → Secrets → Actions
2. Добавьте:
   - `DOKPLOY_WEBHOOK_URL`
   - `DOKPLOY_WEBHOOK_SECRET`

---

### Вариант 3: SSH Deploy (традиционный подход)

```yaml
# Добавьте в .github/workflows/cd.yml
  deploy-via-ssh:
    name: Deploy via SSH
    needs: build-and-deploy
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/master'

    steps:
    - name: Deploy to server
      uses: appleboy/ssh-action@v1.0.0
      with:
        host: ${{ secrets.DEPLOY_HOST }}
        username: ${{ secrets.DEPLOY_USER }}
        key: ${{ secrets.DEPLOY_SSH_KEY }}
        script: |
          cd /opt/dokploy/projects/wordwaverise/wordwaverise-backend
          docker-compose pull
          docker-compose up -d --force-recreate
          docker system prune -f
```

Добавьте секреты:
- `DEPLOY_HOST`: IP вашего сервера
- `DEPLOY_USER`: SSH пользователь
- `DEPLOY_SSH_KEY`: Приватный SSH ключ

---

## Управление приложением

### Через Dokploy UI

#### Просмотр логов
1. Application → Logs
2. Выберите сервис: `app` или `db`
3. Логи обновляются в реальном времени

#### Перезапуск сервисов
1. Application → Overview
2. Нажмите **"Restart"**
3. Выберите сервис или "All"

#### Масштабирование
1. Application → Settings
2. Измените **Replicas**: 1 → 2 (или больше)
3. Нажмите **"Save & Deploy"**

#### Обновление переменных окружения
1. Application → Environment
2. Измените значения
3. Нажмите **"Save"**
4. Нажмите **"Redeploy"** для применения

---

### Через SSH и Docker

```bash
# Подключение к серверу
ssh user@your-server-ip

# Переход в директорию проекта
cd /opt/dokploy/projects/wordwaverise/wordwaverise-backend

# Просмотр статуса
docker-compose ps

# Просмотр логов
docker-compose logs -f app
docker-compose logs -f db

# Перезапуск приложения
docker-compose restart app

# Обновление и перезапуск
docker-compose pull
docker-compose up -d

# Остановка
docker-compose down

# Полная пересборка
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

---

## Мониторинг и логи

### Встроенный мониторинг Dokploy

1. **Dashboard**: Overview метрик (CPU, RAM, Network)
2. **Logs**: Реальное время логов всех сервисов
3. **Metrics**: Детальная статистика использования ресурсов
4. **Alerts**: Настройка уведомлений (email, Slack, Discord)

### Ручной мониторинг

#### Проверка здоровья приложения

```bash
# Health check
curl http://your-server-ip:8080/api/health

# Или через домен
curl https://api.yourdomain.com/api/health
```

#### Мониторинг контейнеров

```bash
# Использование ресурсов
docker stats

# Детали конкретного контейнера
docker inspect wordwaverise-backend

# Логи с временными метками
docker-compose logs -f --timestamps app
```

#### Мониторинг базы данных

```bash
# Подключение к PostgreSQL
docker-compose exec db psql -U wordwaverise_user -d wordwaverise

# Внутри psql:
\l                # Список баз данных
\dt               # Список таблиц
\du               # Список пользователей
SELECT version(); # Версия PostgreSQL
\q                # Выход
```

---

## Troubleshooting

### Проблема: Приложение не запускается

**Решение:**

```bash
# Проверьте логи
docker-compose logs app

# Частые причины:
# 1. База данных не готова - подождите 30-60 секунд
# 2. Неверные переменные окружения
# 3. Порт 8080 занят

# Проверка портов
netstat -tulpn | grep 8080
# или
lsof -i :8080

# Перезапуск с пересборкой
docker-compose down
docker-compose up -d --build
```

---

### Проблема: Ошибка подключения к базе данных

**Решение:**

```bash
# Проверка статуса БД
docker-compose ps db

# Проверка логов БД
docker-compose logs db

# Проверка подключения вручную
docker-compose exec db psql -U wordwaverise_user -d wordwaverise

# Если БД не запущена
docker-compose up -d db

# Проверка переменных окружения
docker-compose exec app env | grep DB
```

---

### Проблема: Ошибка 502 Bad Gateway

**Причины и решения:**

```bash
# 1. Приложение еще не запустилось (подождите 1-2 минуты)
docker-compose logs -f app

# 2. Приложение упало
docker-compose ps
docker-compose restart app

# 3. Неверный порт в конфигурации
# Проверьте docker-compose.yml:
# ports:
#   - "8080:8080"  # должен совпадать с портом приложения
```

---

### Проблема: Нет места на диске

**Решение:**

```bash
# Проверка использования диска
df -h

# Очистка неиспользуемых Docker образов
docker system prune -a -f

# Очистка логов
docker-compose logs --tail=0 -f app > /dev/null 2>&1
sudo rm -rf /var/lib/docker/containers/*/*-json.log

# Очистка volumes (ОСТОРОЖНО! Удалит данные)
# docker volume prune -f
```

---

### Проблема: SSL сертификат не выпускается

**Решение:**

```bash
# 1. Проверьте DNS запись
nslookup api.yourdomain.com

# 2. Проверьте порты 80 и 443
sudo ufw status
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# 3. Проверьте логи Dokploy
docker logs dokploy

# 4. Вручную получите сертификат (если нужно)
sudo certbot certonly --standalone -d api.yourdomain.com
```

---

## Лучшие практики

### 1. Безопасность

```bash
# Используйте сильные пароли
# JWT_SECRET должен быть минимум 256 бит
openssl rand -base64 32

# Регулярно обновляйте зависимости
docker-compose pull
docker-compose up -d

# Настройте firewall
sudo ufw enable
sudo ufw allow 22/tcp   # SSH
sudo ufw allow 80/tcp   # HTTP
sudo ufw allow 443/tcp  # HTTPS
sudo ufw allow 3000/tcp # Dokploy UI (или закройте после настройки)

# Ограничьте доступ к Dokploy UI
# Настройте IP whitelist или VPN
```

---

### 2. Резервное копирование

```bash
# Автоматический бэкап БД (ежедневно в 2:00 AM)
# Создайте cron job:
crontab -e

# Добавьте:
0 2 * * * cd /opt/dokploy/projects/wordwaverise/wordwaverise-backend && docker-compose exec -T db pg_dump -U wordwaverise_user wordwaverise | gzip > /backups/db_$(date +\%Y\%m\%d).sql.gz

# Создайте директорию для бэкапов
sudo mkdir -p /backups
sudo chmod 700 /backups

# Ручной бэкап
docker-compose exec db pg_dump -U wordwaverise_user wordwaverise > backup_$(date +%Y%m%d_%H%M%S).sql

# Восстановление
docker-compose exec -T db psql -U wordwaverise_user wordwaverise < backup.sql
```

---

### 3. Мониторинг и алерты

#### Настройка Uptime Robot (бесплатно)

1. Зарегистрируйтесь на [uptimerobot.com](https://uptimerobot.com)
2. Создайте мониторинг:
   - Type: HTTP(s)
   - URL: `https://api.yourdomain.com/api/health`
   - Interval: 5 минут
3. Настройте уведомления: email, Slack, Telegram

#### Настройка логирования

```yaml
# Обновите docker-compose.yml
services:
  app:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

---

### 4. Производительность

```yaml
# Оптимизация docker-compose.yml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
        reservations:
          cpus: '0.5'
          memory: 256M
```

```bash
# Оптимизация PostgreSQL
# Добавьте в docker-compose.yml:
  db:
    command: postgres -c shared_buffers=256MB -c max_connections=100
```

---

### 5. Обновления и откаты

```bash
# Создайте тег перед обновлением
git tag -a v1.0.0 -m "Release 1.0.0"
git push --tags

# Обновление
git pull origin master
docker-compose up -d --build

# Откат к предыдущей версии
git checkout v1.0.0
docker-compose up -d --build

# Откат к последнему рабочему коммиту
git log --oneline  # Найдите hash
git checkout <commit-hash>
docker-compose up -d --build
```

---

## Полезные команды

### Dokploy CLI

```bash
# Список проектов
dokploy project list

# Список приложений в проекте
dokploy app list --project wordwaverise

# Просмотр логов
dokploy app logs wordwaverise-backend --follow

# Перезапуск
dokploy app restart wordwaverise-backend

# Деплой
dokploy app deploy wordwaverise-backend
```

---

### Docker команды

```bash
# Просмотр всех контейнеров
docker ps -a

# Просмотр образов
docker images

# Очистка системы
docker system prune -a --volumes

# Экспорт контейнера
docker export wordwaverise-backend > backend.tar

# Импорт контейнера
docker import backend.tar

# Копирование файлов из контейнера
docker cp wordwaverise-backend:/app/logs ./logs

# Выполнение команды внутри контейнера
docker-compose exec app sh
```

---

## Дополнительные ресурсы

### Документация
- [Dokploy Docs](https://docs.dokploy.com)
- [Docker Compose Docs](https://docs.docker.com/compose/)
- [Ktor Documentation](https://ktor.io/docs/)
- [PostgreSQL Docs](https://www.postgresql.org/docs/)

### Сообщества
- [Dokploy Discord](https://discord.gg/dokploy)
- [Dokploy GitHub](https://github.com/Dokploy/dokploy)
- [Stack Overflow](https://stackoverflow.com/questions/tagged/dokploy)

---

## Поддержка

При возникновении проблем:

1. Проверьте логи: `docker-compose logs -f`
2. Проверьте документацию выше
3. Создайте issue на GitHub
4. Обратитесь в Dokploy Discord

---

## Чеклист развертывания

Используйте этот чеклист для проверки:

- [ ] VPS/сервер настроен и доступен
- [ ] Docker и Docker Compose установлены
- [ ] Dokploy установлен и доступен
- [ ] Репозиторий на GitHub обновлен
- [ ] Проект создан в Dokploy
- [ ] Приложение добавлено в Dokploy
- [ ] Переменные окружения настроены
- [ ] Домен настроен (опционально)
- [ ] SSL сертификат выпущен (опционально)
- [ ] Приложение успешно развернуто
- [ ] Health check проходит успешно
- [ ] Webhook GitHub → Dokploy настроен
- [ ] Резервное копирование настроено
- [ ] Мониторинг настроен
- [ ] Firewall настроен
- [ ] Документация прочитана и понята

**Поздравляем! Ваше приложение успешно развернуто! 🎉**

---

*Последнее обновление: 2025-11-23*
*Версия документа: 1.0.0*
