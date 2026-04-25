#!/bin/bash
# Run once on the server to get the first Let's Encrypt certificate.
# After this, certbot container handles renewals automatically.

DOMAIN="gitpulse.ru"
EMAIL="your-email@example.com"   # <-- замени на свой email

set -e

echo "=== Устанавливаем certbot ==="
apt-get update -q && apt-get install -y certbot

echo "=== Получаем сертификат для $DOMAIN ==="
# Standalone mode — временно занимает порт 80, поэтому nginx должен быть остановлен
certbot certonly \
  --standalone \
  --non-interactive \
  --agree-tos \
  --email "$EMAIL" \
  -d "$DOMAIN" \
  -d "www.$DOMAIN"

echo ""
echo "=== Готово! Сертификат в /etc/letsencrypt/live/$DOMAIN/ ==="
echo "Теперь запускай: docker compose -f docker-compose.prod.yml up -d"
