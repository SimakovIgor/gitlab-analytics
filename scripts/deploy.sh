#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${PROJECT_DIR}/.env.prod"
if [[ ! -f "${ENV_FILE}" ]]; then
  echo "ERROR: .env.prod not found."
  echo "Copy .env.prod.example to .env.prod and fill in the values."
  exit 1
fi

# shellcheck source=/dev/null
source "${ENV_FILE}"

SERVER_IP="${SERVER_IP:?SERVER_IP is not set in .env.prod}"
SERVER_USER="${SERVER_USER:-root}"
REMOTE_DIR="/opt/gitlab-analytics"

echo "==> Building JAR..."
cd "${PROJECT_DIR}"
./gradlew bootJar

echo "==> Syncing files to ${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}..."
rsync -avz --delete \
  --exclude='.git' \
  --exclude='.gradle' \
  --exclude='.idea' \
  --exclude='*.iml' \
  --exclude='.env*' \
  --exclude='build/classes' \
  --exclude='build/resources' \
  --exclude='build/reports' \
  --exclude='build/tmp' \
  --exclude='build/test-results' \
  "${PROJECT_DIR}/" "${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/"

echo "==> Copying .env.prod to server..."
scp "${ENV_FILE}" "${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/.env"

echo "==> Starting services on server..."
ssh "${SERVER_USER}@${SERVER_IP}" bash <<ENDSSH
  set -e
  cd ${REMOTE_DIR}
  docker compose -f docker/docker-compose.prod.yml --project-name gitlab-analytics --env-file .env up -d --build --remove-orphans
  echo "Waiting for services to start..."
  sleep 15
  docker compose -f docker/docker-compose.prod.yml --project-name gitlab-analytics ps
ENDSSH

echo ""
echo "==> Deploy complete!"
echo "    App:        http://${SERVER_IP}:8080"
echo "    Grafana:    http://${SERVER_IP}:3000"
echo "    Prometheus: http://${SERVER_IP}:9090"
echo "    Portainer:  http://${SERVER_IP}:9000"
