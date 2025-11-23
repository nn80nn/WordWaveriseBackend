@echo off
REM Deploy Check Script for WordWaveriseBackend (Windows)
REM This script checks if the project is ready for deployment

echo.
echo WordWaveriseBackend - Pre-deployment Check
echo ==============================================
echo.

REM Check required files
echo Checking required files...
if exist "Dockerfile" (
    echo [OK] Dockerfile exists
) else (
    echo [FAIL] Dockerfile is missing!
    exit /b 1
)

if exist "docker-compose.yml" (
    echo [OK] docker-compose.yml exists
) else (
    echo [FAIL] docker-compose.yml is missing!
    exit /b 1
)

if exist ".dockerignore" (
    echo [OK] .dockerignore exists
) else (
    echo [FAIL] .dockerignore is missing!
    exit /b 1
)

if exist "build.gradle.kts" (
    echo [OK] build.gradle.kts exists
) else (
    echo [FAIL] build.gradle.kts is missing!
    exit /b 1
)
echo.

REM Check Docker
echo Checking Docker...
docker --version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Docker is installed
) else (
    echo [FAIL] Docker is not installed!
    exit /b 1
)

docker-compose --version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Docker Compose is installed
) else (
    docker compose version >nul 2>&1
    if %errorlevel% equ 0 (
        echo [OK] Docker Compose is installed
    ) else (
        echo [FAIL] Docker Compose is not installed!
        exit /b 1
    )
)
echo.

REM Check Git
echo Checking Git...
git --version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Git is installed
) else (
    echo [FAIL] Git is not installed!
    exit /b 1
)

if exist ".git" (
    echo [OK] Git repository initialized
) else (
    echo [FAIL] Not a Git repository!
    exit /b 1
)
echo.

REM Check environment files
echo Checking environment configuration...
if exist ".env.dokploy" (
    echo [OK] .env.dokploy exists
) else (
    echo [WARN] .env.dokploy not found (optional)
)
echo.

REM Check GitHub Actions
echo Checking CI/CD configuration...
if exist ".github\workflows\ci.yml" (
    echo [OK] CI workflow exists
) else (
    echo [WARN] CI workflow not found
)

if exist ".github\workflows\cd.yml" (
    echo [OK] CD workflow exists
) else (
    echo [WARN] CD workflow not found
)
echo.

echo ==============================================
echo Pre-deployment check completed!
echo.
echo Next steps:
echo   1. Review warnings above (if any)
echo   2. Commit and push changes
echo   3. Follow DOKPLOY_DEPLOYMENT.md for deployment instructions
echo.
pause
