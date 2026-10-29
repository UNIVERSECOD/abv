#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
umask 077
if [[ -e .env ]]; then
  echo '.env artıq mövcuddur; açarlar dəyişdirilmədi.' >&2
  exit 1
fi
command -v openssl >/dev/null
command -v docker >/dev/null
docker compose version >/dev/null
origin="${1:-http://localhost:3000}"
if [[ ! "$origin" =~ ^https?://[a-zA-Z0-9.:_-]+$ ]]; then
  echo 'Ünvan http://server-ip:3000 və ya https://domain formatında olmalıdır.' >&2
  exit 1
fi
set -o noclobber
{
  printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'PGADMIN_DEFAULT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 48)"
  printf 'ENCRYPTION_SECRET_KEY=%s\n' "$(openssl rand -hex 32)"
  printf 'ISAPI_API_KEY=%s\n' "$(openssl rand -hex 32)"
  printf 'CORS_ALLOWED_ORIGINS=%s\n' "$origin"
  printf 'SPRING_PROFILES_ACTIVE=prod\nABV_HTTP_PORT=3000\n'
} > .env
mkdir -p runtime
chmod 700 runtime
chmod 600 .env
echo 'ABV konfiqurasiyası yaradıldı. Konteynerlər hələ başladılmayıb.'
