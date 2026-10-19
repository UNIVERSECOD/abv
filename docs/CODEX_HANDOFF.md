# Codex Project Handoff

Paid balance enforcement (2026-10-20): approved ANNUAL creation/status approval/edit/type or employee change now rejects days beyond remaining with Azerbaijani remaining/requested warning. Own old record is excluded on edit/reapproval; missing opening balance blocks chargeable paid approval; pending/unpaid/legacy nonannual and pre-activation historical days remain unrestricted. Cancellation restores credit. Employee and leave-row database locks serialize writers, refresh waiting employee snapshots, and lock employee IDs in sorted order for swaps/imports. XLSX cumulative overspend throws with physical row number and rolls back the entire transaction. No migration. This supersedes earlier notes that negative approved balances are allowed; historical overdrawn records are not rewritten and can still be cancelled.

Paid/unpaid split (2026-10-20): new-leave UI offers Ödənişli məzuniyyət (existing ANNUAL) and Ödənişsiz məzuniyyət (existing UNPAID); legacy other types remain readable/importable and editable on existing records. No data/type migration. Paid usage debits the preserved opening; approved unpaid days since the same activation date accumulate in a separate unpaidDays counter without reducing remaining, including at zero balance. Cancellation/edit/import dynamically updates both counters. Both approved types retain Tabel/Excel M. Excel templates/exports use the display labels; imports also accept old names and codes. Other historical leave types are not reclassified. No holiday, automatic accrual or insufficient-balance enforcement added.

Immediate vacation debit (2026-10-20): user removed Plan. Approved ANNUAL leave now immediately counts its entire inclusive post-activation range as used, including future days; remaining = preserved opening minus used. Cancellation restores it on the next read. Balance API/UI no longer expose planned/available fields or columns. Opening date, tenant filtering, overlap merging, missing/negative balance handling and deferred holidays/annual replenishment remain unchanged. No new migration. This supersedes the used/planned split below.

Opening leave balance (2026-10-19): user confirmed employee annual_leave_balance is the current remaining balance; preserve it and count from activation day forward. V043 adds a Baku opening date without changing balances. New HR/tenant-scoped `/api/annual-leave/balances` dynamically projects approved ANNUAL requests into used (including today), planned, remaining and available; merges legacy overlaps, excludes earlier dates, reflects edit/cancel/import immediately on next read. UI shows balance table and labels employee field as opening balance. Calendar days, no holiday exclusion/integration, automatic annual credit or entitlement accrual; these remain deferred. Legacy manual annual ledger is untouched. 537 backend tests/package, frontend lint/build, Docker builds/config and isolated PostgreSQL migration passed. Mandatory backup then live MCG backend/frontend restart succeeded; V043 applied, all 10 employee date anchors present, frontend HTTP200 and unauthenticated balances HTTP401. No authenticated browser acceptance or ISAPI/device test/write. Delivery stays codex/mcg. See LEAVE_MANAGEMENT.md.

Backup/download/restore (2026-10-19): Settings → Backup now queues full snapshot/download and inspected, explicitly confirmed restore through a Windows host worker. Mandatory pre-restore database/photo/config backup is persisted to the configured backup folder before any live database swap. Failed health checks roll original databases/photos/configuration back; journaled interrupted swaps recover before accepting new work. Docker CREATE resumes the existing containers; RESTORE pins installed image IDs and preserves host paths/ports/DB connection credentials while restoring application keys. A public opaque-capability status page survives JWT/user restoration. Whole-host operations require head-office HR and a single-tenant installation. Restored device-removal queues are paused pending explicit hardware review. 531 backend/11 frontend tests, PS5.1/7 fixture + real isolated PostgreSQL restore tests, builds, and live CREATE-only/archive-integrity/data-preservation checks passed. No real customer restore, device write, native SCM restore or new Setup EXE verification. See [BACKUP_RESTORE.md](BACKUP_RESTORE.md). Current delivery remains `codex/mcg`.

Leave management (2026-10-18): the MCG `/permissions` sidebar entry is now **İcazə / Məzuniyyət**, with the existing permission tab and a new vacation tab for create/edit/cancel and Excel template/import/full export. Uses existing leave tables; no migration, attendance rewrite or physical-device operation. Approved inclusive vacation dates display **M** in Tabel/UI/Excel (also future/rest dates); pending/rejected leave does not. Existing leave-day work totals remain unchanged. Imports are tenant-validated and atomic, use HR employee codes, and reject duplicates/overlapping non-rejected periods. See [LEAVE_MANAGEMENT.md](LEAVE_MANAGEMENT.md). Keep this delivery on the current `codex/mcg` customer variant.

Scope/navigation clarification (2026-10-13, latest): **İşdə olub + Çıxış yoxdur together belongs only to the DAILY report and its Excel/filter**, not individual entry/exit details or the separate employee-attendance page. DETAILS retains each visit's own times, minutes and status; later open visits never mark earlier closed detail rows as missing-exit. The employee-attendance page/API has its prior single primary status again. Reports opens/reopens on DAILY for today's Asia/Baku date, with Gündəlik first; Dünən selects exactly yesterday for both date bounds. No attendance calculations, data, migrations or device behavior change. This supersedes the earlier shared employee-screen badge scope below.

Latest user clarification (2026-10-13): completed visits followed by an unclosed re-entry must show **both İşdə olub and Çıxış yoxdur**, including today. This supersedes the earlier exclusion of latest-open days from the completed-presence badge. Only completed intervals contribute time in this case; existing break/permission/OT rules are preserved and open visits add no time. A shared read-only status projection feeds employee attendance, daily overview, filters and Excel; leave/permission badges remain independent. Open-only current days still show İşdə. No migration, historical recalculation or device write. Deliver on `codex/mcg`, not standard main.

Completed-presence badge follow-up (2026-10-13): a closed daily visit must show **İşdə olub** alongside permission, overtime, late or early-exit badges. Renaming `PRESENT`/`WORKDAY_COMPLETE` alone was insufficient when the primary status was `ON_PERMISSION`. The report now adds a separate `PRESENT` badge from completed entry/exit evidence, excluding unresolved/latest-open days; permission-only days without a visit do not qualify. Excel and the **İşdə olub** filter use the same status list. Primary status, work duration and all calculations are unchanged by this badge correction. User requested pushing all completed changes; deliver this customer variant on `codex/mcg`.

Status/report OT follow-up (2026-10-13, latest): user requested `Tamamlandı` -> **İşdə olub**, with **İşdə** reserved for an unclosed visit on the current Asia/Baku calendar date. This is a report/UI projection, not a change to device session expiry/pairing or persisted attendance. Previous-day unclosed visits display `Çıxış yoxdur`; completed `PRESENT`/`WORKDAY_COMPLETE`/`CLOSED` display `İşdə olub`. User explicitly approved **only overtime alignment** after identifying the daily-report/Tabel mismatch, then requested a full calculation audit. Daily/monthly/yearly report OT and Excel now share MCG's scheduled-end closed-visit calculation; existing worked duration, permission, tolerance and schedule rules remain. Audit fixes within OT merge overlapping visits and sum seconds before converting to whole minutes/gating at 60, so no physical time is counted twice and exactly one hour split across visits qualifies. This supersedes earlier notes that ordinary report overtime stays on worked-minus-norm. Stored legacy calculation records were not rewritten. See the latest task-ledger entry.

MCG correction (2026-10-13, latest): user explicitly rejected counting exit-to-next-entry gaps as overtime and clarified that a completed assigned day remains green `1` while actual work after scheduled end is below 60 minutes. Example: 08:00–17:00 schedule, 08:00–17:30 attendance -> `1`, zero OT. With 30-minute early-exit tolerance, 16:30 exit -> `1`, but 16:20 -> red `08:20` (no break in these examples). MCG OT now sums only the after-end portions of closed visits; gaps and open visits add nothing. The existing 60-minute gate still includes all eligible minutes once reached. **Do not change existing total-work calculation, schedule/tolerance/permission rules or ordinary reports as part of this correction.** This supersedes the earlier statement that any counted time above the norm must remain a duration, and the completed-day first/last-span OT implementation. See the newest task-ledger entry.

MCG duration/color clarification (2026-10-12, latest): user chose **HH:mm** for both daily durations (`08:24`) and monthly overtime/total-work durations. Only a completed assigned norm within accepted tolerances shows green `1`; counted time above the norm stays a duration. MCG OT is actual counted work **after the assigned shift's end**, enabled at **60 minutes after end per day**; then all eligible after-end minutes count. Early arrival does not count toward this gate. Eligible OT cells are blue, positive work below the norm is red (unless a tolerated full day), remaining cells/zeros are black. Assigned non-working days show **Q/İ**, including future dates; rest-day work remains in total duration but no end-time OT is invented. UI/Excel share these rules and the three monthly columns. OT is already included in total work, never added again. Existing API hour fields, ordinary reports and stored attendance remain unchanged. This supersedes the earlier whole-hour/decimal-total/five-column/zero-rest-day notes below. See the latest task-ledger entry.

MCG layout clarification (2026-10-12, latest): user wants the original compact Tabel layout, with only **Overtime (saat)**, **İş günlərinin sayı**, and **İş saatlarının cəmi** after the daily columns, on screen and in Excel. Full-day/partial-hour summary columns and the large on-screen explanation box were removed. Daily behavior stays `1` only when all assigned-day conditions hold, otherwise whole worked hours, with zero for empty days. API totals and calculations are unchanged. This supersedes older instructions to display five monthly columns. See the latest task-ledger entry for verification.

MCG display/deployment follow-up (2026-10-12): on this workstation the standard source copy is `D:\Projects\Attendra-Standard-20261012` at `526b4e2`; current `D:\Projects\hr_erp_attendra` is on `codex/mcg`. Docker MCG runs at `http://localhost:3001` (backend `127.0.0.1:8082`), with local `runtime/docker-local-ports.yml`; native Windows services continue on their existing ports. User clarified daily Tabel cells: numeric `1` for a complete assigned day, otherwise whole counted hours with minutes discarded (`8h16m -> 8`), and `0` for empty/future days. Monthly **Overtime (s)** remains, with exact monthly totals. DETAILS report/UI/Excel omit overtime. Existing login-session browser and downloaded Excel checks passed; 462 backend tests/package, frontend lint/build and Docker build/config/health passed. Mandatory backups and aggregate HR/photo preservation checks passed. No migration, historical recalculation or physical-device test/write. See latest task-ledger entry.

MCG variant (2026-10-12): user requested a standard source copy at `D:\Attendra-Standard-before-MCG-20261012` (baseline `526b4e2`) and MCG functionality in the existing `D:\hr-erp-back-front` folder. Current work is on **`codex/mcg`, not general main**. The Tabel page/Excel request `mode=MCG`: complete assigned daily shifts (not fixed 8h) show `1`, incomplete days/OT show separate hours, and approved non-deductible permission counts. Stored hours and ordinary report behavior are unchanged. See [MCG_TABEL.md](MCG_TABEL.md) and the newest task-ledger entry. No separate Setup, production/native deployment or live-data copy was requested/performed. Do not merge this customer-specific policy into general main without approval. Older “MCG unimplemented” notes refer to the standard baseline.

Latest backend correction (2026-10-12, user authorized "düzəlt"): delayed directional ENTRY/EXIT punches match by event-time session boundary, not newest open row. Closed flexible intervals are allocated by Asia/Baku calendar day (20:00–02:00 => 4h+2h), including month-start carry-in, permissions and correction recalculation; source/detail sessions and STANDARD/NIGHT stay unchanged. Employee-removal jobs require strict live absence readback after DELETE. Clean backend package passed 426 tests; Docker build/config passed; isolated PostgreSQL/API testing verified replay, 4h+2h Tabel totals and actual backend restart with pending jobs. **No production/native deployment, physical-device test or customer historical recalculation was performed.** See the newest `TASK_STATUS.md` entry for acceptance/deployment steps. Older notes calling delayed pairing and midnight allocation unimplemented/deferred are superseded.

Second-photo recovery (2026-10-12): user explicitly requested the other backed-up photo too. After verifying entry device identity, live employee identity, source hash and enabled retention, that photo was uploaded successfully (HTTP 200), downloaded back and decoded (27,252 bytes). Both originally missing photos on the entry terminal are now recovered. No exit-device write, local-profile restoration or Setup deployment was performed. Evidence: ignored `runtime/audit/approved-ismayil-face-recovery.log`.

Photo recovery confirmed (2026-10-12): user enabled picture retention on the entry terminal and explicitly requested re-upload. Read-only AcsCfg verified `saveFacePic=true`; the same authorized person's original photo was re-sent through the updated production client after identity/hash verification. Upload returned HTTP 200; exact-person photo downloaded and decoded successfully (29,651 bytes). User confirmed it worked. Terminal-wide retention being disabled was the observed blocker; request-level `saveFacePic=true` alone had been insufficient. Only that approved person's photo on the entry terminal was changed. No other photo/local profile restoration or new Setup deployment has been performed. This supersedes the pending-approval/recovery statements below.

Physical photo check (2026-10-12, latest): user authorized one original-photo re-upload to the entry terminal. The updated FDLib request succeeded (HTTP 200), but photo GET remained 404. A read-only `GET /ISAPI/AccessControl/AcsCfg?format=json` then confirmed the terminal-wide **`saveFacePic: false`** setting. The missing request flag alone was NOT the root-cause fix. Original device configuration was backed up under the protected native `recovery/photo-retention-*` directory. No global device settings have been changed. Next step requires explicit approval to enable terminal-wide picture retention and repeat the same single-person upload; it affects future enrollments for all users on that terminal. Existing photo originals remain safe. No new Setup/runtime deployment yet.

Face picture recovery investigation (2026-10-12): native and Docker photo clients were byte-identical; direct read-only photo downloads from both environments returned 404 while the terminal still recognizes faces. Original local photos were located and decoded successfully in the old Docker volume and its latest backup; hashes match. FDLib multipart upload now explicitly requests `saveFacePic: true` per Hikvision's documented protocol. This is a candidate retention fix, not a hardware-confirmed root cause. 34 ISAPI tests/package and Docker image build passed. The running Windows installation/Setup 1.0.2 is unchanged. A one-person physical-device re-upload requires explicit user approval before proceeding; no device write or local photo restoration has been performed in this investigation.

Windows installer correction (2026-10-12): 1.0.1 used the wrong WinSW 2 service-account XML element (`username`), causing LocalSystem registration and PostgreSQL startup failure. 1.0.2 uses `domain`/`user`, verifies/repairs the actual SCM account, discovers incomplete installation paths and snapshots an incomplete stopped cluster before retrying. The user's installation was repaired in place with elevation, then the complete corrected Setup ran successfully (exit 0): all four services run as LocalService and web/API health passes. Mandatory backup, installed file hashes, shortcuts, uninstaller and unchanged configuration/photos were verified. Clean-machine install, reboot without login and uninstall remain release gates. See the newest task-ledger entry; this supersedes the older statement that SCM could not be tested.

Windows installer (2026-10-11): user requested an offline customer Setup without Docker. See `docs/WINDOWS_INSTALLER.md` and `installer/windows/`. NSIS packages Java 21, PostgreSQL 15, Caddy, WinSW and signed Microsoft VC++ runtime; four LocalService services, separate ProgramData storage, mandatory pre-upgrade backup and data-preserving uninstall. Existing Docker backup script also supports protected native.json installations. Native process smoke/restore tests pass; full SCM registration, reboot, upgrade and uninstall on a clean administrator Windows VM are still required. Treat the generated EXE as a test release, not a fully verified customer release. Existing Docker installation/data are untouched.

2026-10-11 permission correction: create, bulk grant and edit validate every requested date against the employee's assigned schedule. Rest days and hourly intervals extending outside the shift are rejected; overnight hours use the shift's work date. No assigned schedule means no permission grant (the attendance fallback remains unchanged). Deductible permission is removed only from time already counted as attendance, overlapping deductions are merged, and remaining actual work above the daily norm still produces overtime. Existing permissions on rest days no longer affect live attendance calculation. 407 backend tests, frontend lint/build and 15 isolated API/report checks passed; see the latest task-ledger entry for deployment.

User acceptance (2026-10-11, latest): merge all `codex/combined-entry-exit-devices` work into main and rebuild the main Docker installation, after renaming visible **Əlavə iş** labels to **Overtime** (including Excel). This supersedes the pending-acceptance restrictions below and in the combined-device document. Physical-device roles and employee lists are not changed automatically by this delivery.

Latest report follow-up on the same separate branch: multi-select daily status filter (OR), overtime badge/filter, consolidated permission statuses, shorter labels and colored positive durations/counts. 397 backend tests and 8 isolated API checks pass; frontend and Docker builds pass. Preview updated; main remains untouched. See the latest task-ledger entry.

2026-10-11 latest branch work: `codex/combined-entry-exit-devices` adds **Giriş və çıxış** devices with tenant/employee/area/day first-last aggregation, excludes flexible schedules, and preserves manual corrections and existing directional pairing. See [COMBINED_DEVICE_ATTENDANCE.md](COMBINED_DEVICE_ATTENDANCE.md). V042 is applied only to the isolated preview. **Do not merge/deploy this feature into main until the user accepts it.** 393 backend tests and 22 isolated API/DB checks pass; frontend and separate Docker builds pass.

Last reviewed: 2026-10-09

Repository: `https://github.com/UNIVERSECOD/hr_erp_attendra.git`

Baseline at review: `b8a389d Persist employee device removal jobs with automatic retry`

Read [TASK_STATUS.md](TASK_STATUS.md) for the latest completed tasks, remaining user requests, acceptance criteria, and verification limits. That task ledger supersedes older progress notes below. Also read [BACKUP_SETUP.md](BACKUP_SETUP.md) and [EMPLOYEE_TERMINATION_JOBS.md](EMPLOYEE_TERMINATION_JOBS.md) when working on those features.

Attendance reports (2026-10-11 correction) display separate completed visits for every shift type. Consecutive automatic entries without an exit share one visible row with the latest entry time. This is a report/Excel projection: the existing daily/Tabel first-entry calculation is preserved. V040 adds `last_entry_time` for debounced repeated entries without overwriting their original check-in. See the task ledger for verification and the separately deferred delayed-event and midnight-allocation work.

Follow-up (2026-10-11): daily presence follows the latest valid entry/exit transition, so older unmatched entries cannot override a later confirmed exit. Genuine re-entry still requires an exit. Source logs remain intact; STANDARD/NIGHT use first-in/last-out when the latest transition is an exit, while FLEXIBLE sums recorded completed intervals. See `ATTENDANCE_REPORT_SECTIONS.md` and the latest task-ledger entry for tests and persisted-summary limits.

This document transfers project context to a new computer or Codex session. Read `AGENTS.md` first, then verify this document against the current source and `git log` before changing code.

User acceptance (2026-10-11): merge `codex/attendance-report-sections` through `6ac1a7c` into `main` and build/deploy with Docker. This includes report sections, shared departments (V041), dashboard metrics and simplified labels. See [ATTENDANCE_REPORT_SECTIONS.md](ATTENDANCE_REPORT_SECTIONS.md) and the latest task-ledger delivery entry. Earlier separate-branch restrictions are superseded by this acceptance.

## 1. System Map

Attendra is a local-network HR and attendance monorepo:

```text
React frontend (:3000)
        |
        v
Spring Boot backend (:8080) ---- PostgreSQL 15
        |
        v
Spring Boot ISAPI bridge (:8081) ---- Hikvision face/access devices
```

- `frontend/`: React 18, TypeScript, Vite, Tailwind, Zustand.
- `backend/`: Java 17, Spring Boot 3, Maven, JPA, Flyway, JWT security.
- `isapi/`: Java 21, Spring Boot, Gradle, Digest-auth device communication.
- `docker-compose.yml`: PostgreSQL, pgAdmin, backend, ISAPI, and frontend.
- Application timezone: `Asia/Baku`.

The browser must call the backend. Device credentials and `ISAPI_API_KEY` must never reach the frontend.

## 2. Important Identities

Several IDs look similar but are not interchangeable:

| Domain | Field | Meaning |
| --- | --- | --- |
| Device | `DeviceConfig.id` | Backend database primary key |
| Device | `DeviceConfig.deviceId` | ISAPI bridge device ID |
| Employee | `Employee.id` | Backend database primary key |
| Employee | `Employee.employeeId` | HR employee code shown in the UI |
| Employee | `Employee.deviceEmployeeNo` | Raw person number used by terminals |
| Security | `tenantId` | Company boundary for tenant-owned data |

Resolve both device IDs and all employee identities before changing sync code.

## 3. Completed Work

- Manual, preview-first device employee reconciliation is implemented and deployed. Read [DEVICE_EMPLOYEE_RECONCILIATION.md](DEVICE_EMPLOYEE_RECONCILIATION.md) for confirmation rules, preservation guarantees, partial results, process-local token limits and verification.

The following behavior is implemented in the current `main` history:

- Employee face uploads have a 5 MB input limit; the ISAPI bridge normalizes device-bound images to JPEG under 190,000 bytes with a maximum dimension of 1024 pixels. Physical firmware compatibility still needs controlled verification.
- Backup settings, storage usage reporting, Windows scheduled backup scripts, and a local native folder-picker helper are implemented. Each Windows installation needs the host helper/task installed; see `BACKUP_SETUP.md`.
- Employee termination preserves history and photos, hides terminated employees from the active list, and marks historical Tabel names red without adding status/date columns to Excel.
- Employee left-side actions include termination instead of photo removal; employee deletion is disabled in the UI while the backend endpoint remains.
- Device-removal jobs are persisted atomically with termination and retried automatically. Partial completion produces a warning, not an all-devices-success message. See `EMPLOYEE_TERMINATION_JOBS.md`.
- Initial setup requires creation of the first admin password on an empty database.
- JWT session protection, password change, expired-session login redirect, and authentication hardening.
- Configurable attendance synchronization interval.
- Employee create/edit validation, including required FIN handling.
- Camera capture through browser video stream, photo preview, replacement, and removal.
- Employee fields persist on initial create, including department, position, timetable, area, and related assignments.
- Employee profile photos are stored locally and synchronized to assigned devices; partial device failure does not undo employee persistence.
- Area model: branches are presented as `Ərazi`; an employee can belong to multiple areas.
- Devices belong to an area. Employees can be assigned to a device individually or through area membership.
- Per-device employee synchronization can resend assigned employees and available faces.
- Device editing preserves the existing encrypted password when the password field is left blank.
- Unreachable devices are reported offline and successful last-sync history is preserved.
- Device user delete behavior supports the Hikvision `UserInfoDetail/Delete` contract used by the terminals.
- Attendance session lifecycle avoids creating a fake `00:00` entry for an unclosed previous-day session.
- Standard and flexible/overnight shifts are handled by the shared attendance schedule resolver.
- Seven-day schedules support separate hours, breaks, entry tolerance, and early-leave tolerance per day.
- Schedules can be assigned to employees individually or by department; employee edit shows the selected timetable.
- Hourly employee permissions include date, time range, reason, status, and whether permitted time is deducted from worked hours.
- Area filtering is available in reporting.
- Employee, department, and position Excel/CSV template, import, and export flows.
- User-facing toast notifications for CRUD, sync, settings, schedules, permissions, data transfer, and session expiry.

Useful commit trail:

```text
b8a389d Persist employee device removal jobs with automatic retry
7bdfd1b Adjust employee action buttons and disable deletion
7ec2661 Add employee termination workflow
d737276 Add Windows backup folder picker
0008dbd Add automated backup management
3bac84e Normalize employee face images for devices
312df80 Limit employee face image uploads
c0f3819 Improve Excel import templates with required fields and choices
af5a351 Add user-facing toast notifications
2d851b1 Synchronize employee faces across assigned devices
2804816 Protect employee field persistence
0b4379a Harden sessions and authentication
bf2c3df Fix area reporting and bulk permission validation
7d62837 Add CSV and Excel master data transfer
9ed52ff Add area-based device employee synchronization
c8f1e88 Add hourly employee permissions
b19a20f Fix attendance session lifecycle
d472a84 Add weekly schedules and unified attendance rules
831ac77 Allow removing employee preview photos
1d6a62d Preserve device last sync history
48da7c0 Mark unreachable devices offline
00f3e4b Fix device edits preserving credentials
263726c Initial commit
```

## 4. Schedule Migration Behavior

`V033__add_weekly_timetable_rules.sql` introduced seven day-specific rules.

Old schedules did not contain weekday information. To avoid deleting or guessing customer data, migration `V033` copied the old start time, end time, break, lateness tolerance, and early-leave tolerance to all seven days as working days.

Example: an old `08:00-17:00` schedule becomes `08:00-17:00` on Monday through Sunday. The schedule and employee assignments remain intact. An administrator must edit the schedule once and mark Saturday/Sunday or other rest days explicitly.

Do not rewrite `V033` after it has run. Any future correction requires a new migration and a clear business rule because some customers may operate seven days.

## 5. Database And Migration State

Backend Flyway migrations currently run from `V001` through `V038`. Recheck the current tree before allocating the next number. The backend history table is `flyway_schema_history_backend`.

Important recent migrations:

- `V030`: first-admin password setup requirement.
- `V031`: attendance sync interval.
- `V032`: device online state.
- `V033` and `V034`: weekly timetable rules.
- `V035`: attendance session integrity.
- `V036`: hourly employee permissions.
- `V037`: employee areas and device assignment sources.
- `V038`: durable employee device-removal jobs; additive, no automatic backfill for previously terminated employees.

Rules for future migrations:

1. Add the next migration number; never edit an applied migration.
2. Prefer nullable columns or safe defaults before tightening constraints.
3. Backfill deterministically and preserve existing rows.
4. Add tenant-aware indexes and constraints where appropriate.
5. Test both a fresh database and an upgraded database containing real-shaped data.

## 6. Device And Face Synchronization

The main boundary is:

```text
Frontend -> backend device API -> ISAPI bridge -> terminal
```

Core invariants:

- Backend-to-ISAPI uses `X-API-Key`.
- ISAPI-to-terminal uses Digest authentication.
- Device passwords remain backend/ISAPI secrets.
- Employee save is local-first. Device failure is reported as a partial sync error and does not roll back the employee row.
- A face upload first updates the local profile image, then sends the face to assigned devices.
- Deleting a local profile photo should still succeed when a terminal is offline; terminal cleanup errors must be reported separately.
- Raw access events remain idempotent on `(device_id, serial_no)` and polling cursors move forward monotonically.

Before a real device write, perform a read-only status/search call and verify bridge ID, IP, and device name. Obtain explicit user approval before create, update, delete, face upload/delete, or cursor reset on physical hardware.

## 7. Authentication And Fresh Computers

- A fresh database with no users opens the initial setup flow.
- Copying only source code to another computer does not copy users or passwords because they are database data.
- Updating an existing installation while keeping its PostgreSQL volume preserves its users and login credentials.
- `.env` is intentionally not tracked. Create it from `.env.example` and transfer real secrets through a secure channel, not Git or Codex chat.
- The old `README.md` default-login note may be stale; current initial-setup behavior is authoritative.

## 8. Safe Backup Before Updating A Customer Computer

Create a timestamped backup directory, then back up both databases from inside PostgreSQL:

```powershell
New-Item -ItemType Directory -Force .\backups | Out-Null
docker exec hic_postgres pg_dump -U hic_user -d hic_backend -Fc -f /tmp/hic_backend.dump
docker cp hic_postgres:/tmp/hic_backend.dump .\backups\hic_backend.dump
docker exec hic_postgres pg_dump -U hic_user -d hic_isapi -Fc -f /tmp/hic_isapi.dump
docker cp hic_postgres:/tmp/hic_isapi.dump .\backups\hic_isapi.dump
docker cp hic_backend:/app/uploads/faces .\backups\faces
Copy-Item .env .\backups\.env.backup
```

Store the backup outside the repository and protect it because it contains personal data and secrets.

Never run:

```powershell
docker compose down -v
```

The `-v` option removes the PostgreSQL and face-image volumes.

## 9. Safe Update On Another Computer

From the existing repository directory:

```powershell
git status
git remote -v
git fetch --prune origin
git pull --ff-only origin main
docker compose config
docker compose up -d --build
docker compose ps
```

Do not use `git reset --hard` when the customer computer has uncommitted changes unless those changes have been inspected and backed up.

After startup, verify:

1. Login with the customer's existing credentials.
2. Existing employees, schedules, schedule assignments, areas, devices, and photos are present.
3. Backend and ISAPI health endpoints respond.
4. Device status checks work before running a sync.
5. One read-only employee/device lookup works.
6. Only with user approval, test one controlled device synchronization.

## 10. Verification Checklist

Frontend:

```powershell
cd frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run build
```

Backend:

```powershell
cd backend
mvn test
mvn package -DskipTests
```

ISAPI:

```powershell
cd isapi
.\gradlew.bat test
```

Docker:

```powershell
docker compose config
docker compose up -d --build
docker compose ps
docker compose logs --tail 100 backend isapi frontend
```

At commit `af5a351`, frontend lint and production build passed. Docker CLI and Maven were unavailable in that Codex execution environment, so Docker and backend tests were not rerun for the frontend-only toast change. Do not convert that limitation into a claim that those checks passed.

Latest verification at `b8a389d` (2026-10-09): all 279 backend tests passed; frontend lint and production build passed; backend/frontend Docker builds and Compose configuration validation passed. Both services were updated locally without deleting volumes. Migration V038 applied successfully, backend `/api/health` and frontend returned HTTP 200. Device tests used mocks; physical terminal deletion and ISAPI tests were not rerun for this backend/frontend job change. See the task ledger for remaining acceptance checks.

## 11. Remaining Or Deferred Work

- Follow the prioritized user backlog in `TASK_STATUS.md`: finish Tabel position filtering, implement a usable audit journal, and leave Attendra MCG until last. Obtain user confirmation before starting each next feature.
- Multi-project daily tracking is deferred: an employee working across multiple projects, project transitions, and hours per project are not implemented.
- Physical Hikvision behavior still requires controlled end-to-end verification against each firmware/device model when device-related code changes.
- Continue security hardening without breaking local-network use; validate allowed device addresses and never expose credentials to the browser.
- Review older broad documentation such as `PROJECT_OVERVIEW.md` against source before relying on it; parts of it describe earlier intended behavior rather than the current implementation.

## 12. Starting Prompt For A New Codex

Use this prompt after cloning or pulling the repository:

> Read `AGENTS.md` and `docs/CODEX_HANDOFF.md`, then inspect the current Git status and source. Treat source and migrations as authoritative. Preserve the existing database and device behavior, explain the proposed change before editing, run the relevant tests, and do not write to physical devices without my explicit approval.
