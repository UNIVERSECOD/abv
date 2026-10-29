# ABV image delivery — 2026-10-29

Application commit: `d4b5b44923abe502d46c44f4a67f80c752c6403f`.
Package: `ABV-Ubuntu-20261029-d4b5b44.tar.gz`, 388,712,219 bytes, linux/amd64.
SHA256: `c1a0a5c5556d51c1f9fefaf7b5ad875d564876b784645f4725d1562772288952`.

The archive is a local delivery artifact, excluded from Git. It contains only
the final ABV backend/ISAPI/frontend images, PostgreSQL 15, and the allowlisted
files from `deploy/ubuntu-images`. No customer database, photos, real `.env`,
repository or application source tree is included. Java bytecode and browser
JavaScript remain inspectable; this delivery does not add obfuscation/licensing.

## Verification

- Final backend/ISAPI containers contain `/app/app.jar`, without `/app/src` or
  `/app/.git`. JAR entries contain no Java/TypeScript source files, source maps,
  `.env` or `.git` entries. Required SQL migrations remain inside the JARs.
- Final frontend contains the ABV title and no `.map`, `.ts`, `.tsx` or `.env`
  files under the served static root; no `/app` build workspace is present.
- The exported `abv-images.tar` successfully loaded all four image tags.
- All seven payload SHA256 checks passed. The compressed archive lists only
  eight expected payload files, including `SHA256SUMS`.
- Both Bash scripts passed syntax validation. Linux fixture checks passed for
  generated key lengths, restricted file/directory permissions, LAN/local
  binding, invalid origins and preservation of an existing `.env`.
- The exact delivered installer passed with a mock Docker CLI: checksum
  validation, image-load command, no-build/no-pull start, repeated-install
  rejection and corrupted-payload rejection. Real image loading and service
  startup were tested separately against Docker Desktop's Linux engine.
- The delivered Compose and loaded versioned images started in an isolated
  internal Docker network with tmpfs databases, runtime and faces. PostgreSQL
  became healthy; 46 backend migrations applied; both applications started;
  frontend ABV title, backend health, initial admin setup/login, authenticated
  STANDARD Tabel and XLSX export passed. No physical device was reachable or
  configured. Temporary containers were removed after verification.

Application code and image contents are unchanged from the previous ABV test
run (621 backend, 57 frontend, 35 ISAPI tests); those suites were not rerun for
this packaging-only change. No customer container was rebuilt or restarted.
Native Ubuntu-server installation, reboot and physical-device acceptance
remain unverified. Ubuntu host backup automation is not part of this package.

## Rebuilding a delivery

Build and verify the intended `abv-*:latest` images first, then use
`scripts/export-image-delivery.ps1` with the matching application commit and
a unique version. The exporter snapshots versioned tags, records image IDs,
exports only runtime images, calculates all payload hashes, and creates a
compressed bundle with an external checksum. See `README_ABV.md`.
