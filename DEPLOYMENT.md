# CI/CD Deployment Guide

This document describes the CI/CD setup for WordWaveriseBackend using GitHub Actions.

## Overview

The project uses two GitHub Actions workflows:
- **CI** (`ci.yml`) - Runs on pull requests and pushes to main branches
- **CD** (`cd.yml`) - Builds and publishes Docker images on pushes to master/main

## CI Workflow

### Triggers
- Push to `master`, `main`, or `develop` branches
- Pull requests to `master`, `main`, or `develop` branches

### Jobs

#### 1. Build and Test
- Checks out code
- Sets up JDK 11
- Caches Gradle dependencies
- Builds the project
- Runs tests
- Uploads test results as artifacts

#### 2. Code Quality Checks
- Runs code style checks
- Runs static analysis

## CD Workflow

### Triggers
- Push to `master` or `main` branches
- Manual workflow dispatch

### Jobs

#### Build and Deploy
1. **Build Application**
   - Builds project with Gradle
   - Runs tests
   - Creates Fat JAR

2. **Docker Image**
   - Builds Docker image using Ktor plugin
   - Tags image with:
     - Branch name
     - Git SHA
     - `latest` (for default branch)
   - Pushes to GitHub Container Registry (ghcr.io)

3. **Artifacts**
   - Uploads Fat JAR for download

## Setup Instructions

### 1. Enable GitHub Container Registry

The workflows automatically use GitHub Container Registry (ghcr.io). No additional secrets needed for pushing images.

### 2. Make Docker Images Public (Optional)

By default, images are private. To make them public:
1. Go to your repository on GitHub
2. Click on "Packages" (right sidebar)
3. Click on your package
4. Click "Package settings"
5. Scroll to "Danger Zone"
6. Click "Change visibility" → "Public"

### 3. Production Server Setup

#### Prerequisites
- Docker and Docker Compose installed
- Git installed
- Server accessible via SSH

#### Initial Setup

1. **Clone the repository on your server:**
   ```bash
   git clone https://github.com/YOUR_USERNAME/WordWaveriseBackend.git
   cd WordWaveriseBackend
   ```

2. **Create production environment file:**
   ```bash
   cp .env.production .env
   ```

3. **Edit `.env` with your values:**
   ```bash
   nano .env
   ```

   Important values to change:
   - `GITHUB_USERNAME` - Your GitHub username
   - `POSTGRES_PASSWORD` - Strong database password
   - `JWT_SECRET` - Long random string (at least 256 bits)

4. **Login to GitHub Container Registry:**
   ```bash
   echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_USERNAME --password-stdin
   ```

   To get a token:
   - Go to GitHub → Settings → Developer settings → Personal access tokens
   - Create token with `read:packages` scope

5. **Start the application:**
   ```bash
   docker-compose pull
   docker-compose up -d
   ```

6. **Check logs:**
   ```bash
   docker-compose logs -f app
   ```

7. **Verify health:**
   ```bash
   curl http://localhost:8080/api/health
   ```

### 4. Automated Deployment (Optional)

To enable automated deployment to your server:

1. **Generate SSH key pair on your local machine:**
   ```bash
   ssh-keygen -t ed25519 -C "github-actions" -f github-actions-key
   ```

2. **Add public key to server:**
   ```bash
   ssh-copy-id -i github-actions-key.pub user@your-server.com
   ```

3. **Add secrets to GitHub repository:**
   - Go to Settings → Secrets and variables → Actions
   - Add the following secrets:
     - `DEPLOY_HOST` - Your server IP or domain
     - `DEPLOY_USER` - SSH username
     - `DEPLOY_KEY` - Contents of `github-actions-key` (private key)

4. **Uncomment deployment job in `.github/workflows/cd.yml`:**
   ```yaml
   deploy:
     name: Deploy to Server
     needs: build-and-deploy
     runs-on: ubuntu-latest
     if: github.ref == 'refs/heads/master'

     steps:
     - name: Deploy to production server
       uses: appleboy/ssh-action@v1.0.0
       with:
         host: ${{ secrets.DEPLOY_HOST }}
         username: ${{ secrets.DEPLOY_USER }}
         key: ${{ secrets.DEPLOY_KEY }}
         script: |
           cd /path/to/WordWaveriseBackend
           docker-compose pull
           docker-compose up -d
           docker system prune -f
   ```

5. **Update the path in the script:**
   Replace `/path/to/WordWaveriseBackend` with actual path on your server

## Manual Deployment

If you prefer manual deployment:

1. **On your server, pull latest changes:**
   ```bash
   cd /path/to/WordWaveriseBackend
   docker-compose pull
   docker-compose up -d
   ```

2. **View logs:**
   ```bash
   docker-compose logs -f
   ```

3. **Restart specific service:**
   ```bash
   docker-compose restart app
   ```

4. **Stop services:**
   ```bash
   docker-compose down
   ```

## Monitoring

### Application Health
```bash
curl http://your-server:8080/api/health
```

### Container Status
```bash
docker-compose ps
```

### View Logs
```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f app
docker-compose logs -f db
```

### Database Access
```bash
docker-compose exec db psql -U wordwaverise_user -d wordwaverise
```

## Troubleshooting

### Container won't start
```bash
# Check logs
docker-compose logs app

# Restart services
docker-compose restart

# Full rebuild
docker-compose down
docker-compose pull
docker-compose up -d
```

### Database connection issues
```bash
# Check database is running
docker-compose ps db

# Check database logs
docker-compose logs db

# Test database connection
docker-compose exec app curl -f http://localhost:8080/api/health
```

### Image pull failures
```bash
# Re-login to registry
echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_USERNAME --password-stdin

# Pull manually
docker pull ghcr.io/YOUR_USERNAME/wordwaverisebackend:latest
```

## Backup and Restore

### Backup Database
```bash
docker-compose exec db pg_dump -U wordwaverise_user wordwaverise > backup.sql
```

### Restore Database
```bash
docker-compose exec -T db psql -U wordwaverise_user wordwaverise < backup.sql
```

## Security Considerations

1. **JWT Secret**: Use a strong, random string (at least 256 bits)
2. **Database Password**: Use a strong, unique password
3. **CORS**: Update `HTTP.kt` to restrict allowed origins in production
4. **HTTPS**: Use a reverse proxy (nginx/traefik) with SSL certificates
5. **Firewall**: Only expose necessary ports (80, 443, 22)
6. **Updates**: Regularly update Docker images and dependencies

## Useful Commands

```bash
# View running containers
docker-compose ps

# Stop all services
docker-compose down

# Start services
docker-compose up -d

# View logs
docker-compose logs -f

# Restart app only
docker-compose restart app

# Clean up old images
docker system prune -a

# Update to latest image
docker-compose pull && docker-compose up -d
```

## Support

For issues or questions:
- Check GitHub Actions logs in the "Actions" tab
- Review application logs: `docker-compose logs -f app`
- Check database logs: `docker-compose logs -f db`
