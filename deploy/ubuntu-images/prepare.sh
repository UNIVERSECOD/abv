#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
umask 077
if [[ -e .env ]]; then
  echo 'Dayandırıldı: .env artıq var. Mövcud şifrələr dəyişdirilmədi.' >&2
  exit 1
fi
command -v openssl >/dev/null
command -v docker >/dev/null
docker compose version >/dev/null
origin="${1:-http://localhost:3000}"
if [[ ! "$origin" =~ ^https?://[a-zA-Z0-9._-]+(:[0-9]{1,5})?$ ]]; then
  echo 'Ünvan http://SERVER_IP:3000 və ya https://domain formatında olmalıdır.' >&2
  exit 1
fi
bind_address=0.0.0.0
if [[ "$origin" == 'http://localhost:3000' || "$origin" == 'http://127.0.0.1:3000' || "$origin" == https://* ]]; then
  bind_address=127.0.0.1
fi
db_password=$(openssl rand -hex 32)
jwt_secret=$(openssl rand -hex 48)
encryption_key=$(openssl rand -hex 32)
isapi_key=$(openssl rand -hex 32)
# Prevent overwriting a file created concurrently.
set -o noclobber
{
  printf 'POSTGRES_PASSWORD=%s\n' "$db_password"
  printf 'JWT_SECRET=%s\n' "$jwt_secret"
  printf 'ENCRYPTION_SECRET_KEY=%s\n' "$encryption_key"
  printf 'ISAPI_API_KEY=%s\n' "$isapi_key"
  printf 'APP_ORIGIN=%s\n' "$origin"
  printf 'ABV_BIND_ADDRESS=%s\n' "$bind_address"
} > .env
mkdir -p runtime
chmod 700 runtime
chmod 600 .env
echo 'Konfiqurasiya yaradıldı. Şifrələr ekrana çıxarılmadı.'
