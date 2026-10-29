# Attendra Development Rules

Read `docs/CODEX_HANDOFF.md` before changing this repository. Treat the checked-out source, Flyway migrations, and current Git history as authoritative when documentation is stale.

## Change Process

1. Inspect the affected frontend, backend, ISAPI, migration, and test paths before proposing a change.
2. Tell the user what will change before editing.
3. Keep changes small, readable, and consistent with the existing project structure.
4. Preserve existing behavior outside the requested feature.
5. Run focused tests first, then the full relevant build and Docker checks where tooling is available.
6. Report any check that could not be run.

## Data Safety

- Existing customer data, login credentials, employee photos, schedules, assignments, and device mappings must survive updates.
- Flyway migrations must be additive and backward compatible. Never edit a migration that may already have run; add a new numbered migration instead.
- Never use `docker compose down -v`, delete Docker volumes, recreate the database, or run destructive SQL unless the user explicitly requests it after a verified backup.
- Do not replace or commit a real `.env`. Do not log, document, or commit device passwords, JWT secrets, encryption keys, API keys, biometric bytes, or customer data.
- Keep tenant filtering on all tenant-owned reads and writes.

## Device And ISAPI Safety

- Frontend calls the backend; the backend proxies device operations to the ISAPI service.
- `DeviceConfig.id` is the backend database ID. `DeviceConfig.deviceId` is the ISAPI bridge device ID. Resolve the correct one before any device call.
- `Employee.id` is the database primary key, `Employee.employeeId` is the HR code, and `Employee.deviceEmployeeNo` is the terminal identity. Do not interchange them.
- Preserve raw access events before deriving attendance.
- Employee persistence must not become a distributed transaction with physical devices. Local employee data survives device-sync failure and the UI must show partial failure.
- Before a physical-device write, verify the target with a read-only status/search call by bridge ID, IP, and name, then obtain explicit user authorization. Tests must use mocks and must not contact real devices.

## Product Rules

- UI language is Azerbaijani-first.
- Do not build a separate mobile version. Keep the existing responsive web UI working.
- Field validation stays beside the relevant field. Toasts summarize completed operations, errors, warnings, and session expiry.
- Areas are stored through the existing branch domain and shown as `Ərazi` in the UI.
- Employees may belong to multiple areas and may be assigned to devices individually or through area membership.
- Existing schedule assignments must remain intact. Weekly rules are day-specific; legacy schedules were migrated to the same hours on all seven days and require an administrator to mark rest days explicitly.
- Attendance times are interpreted in `Asia/Baku`.

## Verification Commands

Run what is relevant to the change:

```powershell
cd frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run build

cd ..\backend
mvn test
mvn package -DskipTests

cd ..\isapi
.\gradlew.bat test

cd ..
docker compose config
docker compose up -d --build
docker compose ps
```

Do not claim Docker, backend, ISAPI, or physical-device verification when the required runtime or hardware was unavailable.

## Git Delivery

- Target repository: `https://github.com/UNIVERSECOD/abv.git`.
- Use author `UNIVERSECOD <leylaha@code.edu.az>` for requested delivery commits.
- Do not stage unrelated files or local secrets.
- The user currently expects completed, verified changes to be pushed to `main`.

## ABV Variant

Read README_ABV.md. The Tabel UI uses STANDARD only, rounds daily worked minutes down at 00–30 and up at 31–59, and sums displayed daily hours. Preserve raw summaries and other attendance reports. This repository is separate from the original Attendra repository.
