#!/bin/bash

# Deploy Check Script for WordWaveriseBackend
# This script checks if the project is ready for deployment

set -e

echo "🔍 WordWaveriseBackend - Pre-deployment Check"
echo "=============================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check functions
check_pass() {
    echo -e "${GREEN}✓${NC} $1"
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    exit 1
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
}

# 1. Check required files
echo "📁 Checking required files..."
required_files=("Dockerfile" "docker-compose.yml" ".dockerignore" "build.gradle.kts")
for file in "${required_files[@]}"; do
    if [ -f "$file" ]; then
        check_pass "$file exists"
    else
        check_fail "$file is missing!"
    fi
done
echo ""

# 2. Check Docker
echo "🐳 Checking Docker..."
if command -v docker &> /dev/null; then
    check_pass "Docker is installed ($(docker --version))"
else
    check_fail "Docker is not installed!"
fi

if command -v docker-compose &> /dev/null || docker compose version &> /dev/null; then
    check_pass "Docker Compose is installed"
else
    check_fail "Docker Compose is not installed!"
fi
echo ""

# 3. Check Git
echo "📦 Checking Git status..."
if [ -d ".git" ]; then
    check_pass "Git repository initialized"

    # Check for uncommitted changes
    if [ -n "$(git status --porcelain)" ]; then
        check_warn "You have uncommitted changes"
        git status --short
    else
        check_pass "No uncommitted changes"
    fi

    # Check remote
    if git remote -v | grep -q "origin"; then
        check_pass "Git remote 'origin' is configured"
    else
        check_fail "Git remote 'origin' is not configured!"
    fi
else
    check_fail "Not a Git repository!"
fi
echo ""

# 4. Build test
echo "🔨 Testing Docker build..."
if docker build -t wordwaverise-test:latest . > /dev/null 2>&1; then
    check_pass "Docker build successful"
    docker rmi wordwaverise-test:latest > /dev/null 2>&1
else
    check_fail "Docker build failed! Check Dockerfile"
fi
echo ""

# 5. Check environment files
echo "🔐 Checking environment configuration..."
if [ -f ".env.dokploy" ]; then
    check_pass ".env.dokploy exists"

    # Check for placeholder values
    if grep -q "CHANGE_ME" .env.dokploy; then
        check_warn ".env.dokploy contains placeholder values (CHANGE_ME)"
    fi
else
    check_warn ".env.dokploy not found (optional)"
fi
echo ""

# 6. Check GitHub Actions
echo "⚙️  Checking CI/CD configuration..."
if [ -f ".github/workflows/ci.yml" ]; then
    check_pass "CI workflow exists"
else
    check_warn "CI workflow not found"
fi

if [ -f ".github/workflows/cd.yml" ]; then
    check_pass "CD workflow exists"
else
    check_warn "CD workflow not found"
fi
echo ""

# 7. Security checks
echo "🔒 Security checks..."
if grep -q "anyHost()" src/main/kotlin/n/startapp/HTTP.kt 2>/dev/null; then
    check_warn "CORS is configured with anyHost() - consider restricting in production"
fi

if [ -f ".env" ]; then
    if grep -qE "(password|secret|key).*=.{1,10}$" .env 2>/dev/null; then
        check_warn "Weak passwords detected in .env file"
    fi
fi
echo ""

# Summary
echo "=============================================="
echo -e "${GREEN}✓${NC} Pre-deployment check completed!"
echo ""
echo "Next steps:"
echo "  1. Review warnings above (if any)"
echo "  2. Commit and push changes: git add . && git commit -m 'Ready for deployment' && git push"
echo "  3. Follow DOKPLOY_DEPLOYMENT.md for deployment instructions"
echo ""
