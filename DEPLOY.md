# Деплой WordWaverise Backend

Схема: **GitHub Actions собирает образ → GHCR → Dokploy тянет готовый образ.**

Этот файл описывает актуальный процесс. Более старые документы
(`DOKPLOY_DEPLOYMENT.md`, `QUICKSTART_DOKPLOY.md`) описывают вариант со
сборкой из исходников силами Dokploy — он больше не используется.

## Как это работает

`.github/workflows/cd.yml` на push в `master`:

1. `test` — `./gradlew build` (компиляция + тесты). Падает → дальше ничего не идёт.
2. `publish` — собирает Docker-образ из `Dockerfile` и пушит в
   `ghcr.io/nn80nn/wordwaverisebackend` с тегами `latest` и `sha-<commit>`.
3. `deploy` — дёргает Deploy Webhook Dokploy (секрет `DOKPLOY_WEBHOOK_URL`).
   Если секрет не задан — шаг пишет warning и не падает.

`.github/workflows/ci.yml` — сборка и тесты на pull request и push в `develop`
(на `master` их прогоняет CD, дублировать не нужно).

`docker-compose.yml` больше **не собирает** проект: сервис `app` берёт готовый
образ `ghcr.io/nn80nn/wordwaverisebackend:${IMAGE_TAG:-latest}` с
`pull_policy: always`, чтобы Dokploy при деплое подтягивал свежий образ.
Сервис `db` (postgres) не изменился.

## Разовая настройка

### 1. Доступ Dokploy к GHCR

Образ в GHCR по умолчанию **приватный**. Нужно одно из двух:

- **Проще:** сделать пакет публичным —
  GitHub → репозиторий → Packages → `wordwaverisebackend` → Package settings →
  Change visibility → Public.
- **Или:** Dokploy → Settings → Registry → Add Registry:
  - Registry URL: `ghcr.io`
  - Username: `nn80nn`
  - Password: GitHub PAT (classic) со скоупом `read:packages`

### 2. Autodeploy оставить включённым, но убрать push-вебхуки из GitHub

Тумблер **Autodeploy** в Dokploy гейтит и сам эндпоинт вебхука: при
выключенном тумблере вызов из Actions получает
`400 {"message":"Automatic deployments are disabled for this compose"}`.
Поэтому Autodeploy должен быть **включён**.

Чтобы при этом деплой не стартовал раньше времени, нужно убрать прямые
уведомления GitHub → Dokploy: GitHub → Settings → Webhooks → удалить хуки,
которые бьют в `.../api/deploy/compose/...`. Иначе push дёрнет Dokploy сразу,
за 2–4 минуты до публикации нового образа, и задеплоится предыдущий.

Итог: Autodeploy on + ноль push-вебхуков в репозитории = единственный, кто
запускает деплой, это шаг `deploy` в конце workflow.

### 3. Deploy Webhook

1. Dokploy → приложение → Deployments → скопировать **Webhook URL**.
2. GitHub → Settings → Secrets and variables → Actions → New repository secret:
   - Name: `DOKPLOY_WEBHOOK_URL`
   - Value: скопированный URL

## Переменные окружения

Все env задаются в Dokploy UI → Environment, список — в `.env.dokploy`.
Дополнительно можно задать `IMAGE_TAG`, чтобы закрепить конкретную сборку.

## Откат на конкретную сборку

Каждый билд помечается тегом `sha-<commit>`. Чтобы откатиться:
Dokploy → Environment → `IMAGE_TAG=sha-1a2b3c4` → Redeploy.

Вернуться на текущую версию — убрать `IMAGE_TAG` (по умолчанию `latest`).

## Диагностика шага деплоя

Шаг `deploy` печатает ответ Dokploy и падает, если тот отказал:

| Ответ | Что значит |
|-------|------------|
| `200 Compose deployed successfully` | всё хорошо, деплой запущен |
| `400 Automatic deployments are disabled for this compose` | выключен тумблер Autodeploy — включить (см. шаг 2) |
| `Branch Not Match` | Dokploy не смог сопоставить ветку из payload с настроенной в приложении |
| `DOKPLOY_WEBHOOK_URL secret is not set` (warning) | не задан секрет, шаг пропущен |
