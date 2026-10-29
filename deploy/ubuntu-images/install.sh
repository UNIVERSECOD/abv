#!/usr/bin/env bash
# Fresh installations only. Updates require a backup and the existing .env.
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ -e .env ]]; then
  echo 'Dayandırıldı: bu qovluq artıq hazırlanıb. Yeniləmə üçün README.md-ni oxuyun.' >&2
  exit 1
fi
command -v docker >/dev/null
command -v openssl >/dev/null
docker compose version >/dev/null
if [[ "$(docker info --format '{{.OSType}}/{{.Architecture}}')" != linux/x86_64 && "$(docker info --format '{{.OSType}}/{{.Architecture}}')" != linux/amd64 ]]; then
  echo 'Bu paket linux/amd64 Docker serveri üçündür.' >&2
  exit 1
fi
sha256sum -c SHA256SUMS
docker load -i abv-images.tar
bash prepare.sh "${1:-http://localhost:3000}"
docker compose config --quiet
docker compose up -d --no-build --pull never
docker compose ps
echo 'İlkin admin kodu: runtime/initial-setup-token (backend açıldıqdan sonra yaranır).'
echo 'Brauzer ünvanı və son yoxlamalar README.md-dədir.'
