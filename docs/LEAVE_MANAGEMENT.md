# İcazə / Məzuniyyət

The existing `/permissions` route now has **İcazələr** and **Məzuniyyətlər** tabs. Hourly/full-day permissions retain their existing API and calculation rules. Leave requests use the existing `leave_requests` and `leave_types` tables; no migration or stored attendance rewrite is required.

## Leave management

Paid/unpaid clarification (2026-10-20): the new-record selector offers **Ödənişli məzuniyyət** (existing ANNUAL) and **Ödənişsiz məzuniyyət** (existing UNPAID). Existing records of other leave types remain readable/editable; their types and accounting are not rewritten. Paid approved days immediately debit the opening balance. Approved unpaid days are separately counted from the same activation date as `unpaidDays`, including future dates and zero/missing paid balances; cancellation decreases this counter. It counts unique inclusive days, not number of requests. Both types still show M in Tabel/Excel. Excel display labels change, while the importer accepts the original names, new labels and existing codes. No schema migration or insufficient-balance block is included.

Verification: 542 backend tests, 11 frontend tests, lint/build and Docker config/build passed. Isolated live API checks covered paid debit, unpaid counters, switching both ways, cancellation, unpaid approval at zero balance, both M markers without work credit, and real unpaid XLSX import/export; the existing 27 balance/API regression checks passed too. Browser creation increased unpaid days 3→6 while remaining stayed 20, and cancellation returned unpaid days to 3. Test backend/database had no device access; synthetic environment was removed afterward. Local evidence: ignored `runtime/audit/verify-paid-unpaid.ps1` and `paid-unpaid-balance.png`. Mandatory backup before MCG backend/frontend update. No physical-device or ISAPI verification was needed/run.

- HR users can create, edit and cancel a leave request. The form defaults to **Təsdiqlənib**; **Gözləmədə** and **Rədd edilib** are also available. API requests omitting status retain the prior `PENDING` default.
- Cancellation changes status to `REJECTED`, preserving history. The UI does not delete leave records.
- Dates are inclusive. Only approved leave produces **M** in Tabel and its Excel export, including future dates, rest days and month boundaries. Leave takes display precedence over holiday/permission codes. Pending/rejected requests produce no vacation marker.
- Existing leave days were already excluded from Tabel work totals. Changing their label from `Q/I` to `M` adds no worked days, work hours or overtime. Other permission, schedule and MCG calculation rules remain unchanged.
- Employee and leave-type references are validated inside the current tenant. Invalid date ranges, exact duplicates (including cancelled records), and overlapping non-rejected requests are rejected. Edit the existing cancelled record to restore it.

## Excel

The vacation tab offers **Şablon endir**, **Excel import**, and **Ümumi cədvəli ixrac et**. Full export includes all vacation records and statuses, regardless of the on-screen filters.

Template columns:

| Column | Meaning |
| --- | --- |
| Əməkdaş ID | Required HR employee code; never the database ID or terminal identity |
| Əməkdaş | Optional name for reference |
| FIN | Optional identity check; if supplied it must match the selected employee |
| Məzuniyyət növü | Required existing leave-type name or code, e.g. `ANNUAL` |
| Başlanğıc tarixi | Required, preferably `YYYY-MM-DD` |
| Bitmə tarixi | Required, inclusive and on/after the start |
| Vəziyyət | `Təsdiqlənib`, `Gözləmədə`, `Rədd edilib`, or their API enum values; blank means approved |

The template includes employee/type/status choices and an employee reference sheet. XLSX/XLS import uses the existing 5 MB / 5,000 row limits. Every row is validated before writes. Any invalid row rejects the entire import and returns the physical Excel row number; there is no partial import. Re-importing exported or previously imported existing records does not create duplicates.

## Balance acceptance verification — 2026-10-20

Tested the installed MCG backend/frontend images against a fresh PostgreSQL 15 database on a separate internal Docker network. Only the test frontend had a host port; no customer database/config/photos were copied, no devices were configured, and the backend's ISAPI destination was an unreachable loopback port.

- 27 API assertions passed: preserved opening balance; approved current/future split; date, employee and type edits; cancellation/reapproval/deletion; pending and sick leave exclusion; activation-date clipping; year boundary; visible negative/unknown balances; overlap rejection; tenant isolation; unauthenticated and employee-role denial; real XLSX import, duplicate rejection, all-or-nothing invalid import and imported cancellation.
- Browser form submission for a 20-day opening balance and inclusive 20–22 October leave immediately displayed used 1, planned 2, remaining 19, available 17. Cancelling through the confirmation dialog immediately restored used/planned 0 and remaining/available 20. Cancelled records remained in history.
- Tabel approval displayed M without credited work hours; cancellation restored the original cell and balance. Full Excel export remained downloadable. The persisted opening balance was still 20 after these operations.
- Complete backend suite: 537 tests, zero failures/errors. Frontend: 11 tests passed; lint and production build passed. No application code correction was necessary. ISAPI/physical-device tests were not run for this balance-only verification.

Local synthetic evidence is kept under ignored `runtime/audit/`: `verify-leave-balances.ps1`, `verify-balance-tabel.ps1`, `leave-balance-created.png` and `leave-balance-cancelled.png`. Temporary test containers/network were removed after verification.

Endpoints are under `/api/leaves`: existing GET/POST, new `PUT /{id}`, existing `PUT /{id}/status`, plus `GET /template`, `GET /export`, and multipart `POST /import` (`file`). Downloads are XLSX. No device operations are involved.

## Verification

### Automatic opening-balance projection (2026-10-19)

`GET /api/annual-leave/balances` is HR-only and tenant-scoped. V043 preserves the existing employee `annual_leave_balance` as the opening balance and adds its Baku activation date. Only approved `ANNUAL` requests on/after that date debit it, including already-entered future requests. Past days before activation are never debited again. New employees receive their own creation-date anchor. Existing annual-leave ledger rows are left intact; the new opening projection does not reinterpret their manual historical usage.

User clarification on 2026-10-20 removed Plan: all approved days on/after activation are immediately used, including future dates. Remaining = opening minus used. The API and screen expose no planned/available values. Both dates are inclusive and calendar days are used, including weekends; holiday integration/exclusion is deferred by user request. Overlapping legacy requests count each date once. Cancellation, editing, deletion and atomic Excel imports are reflected on the next read without synchronization writes. Missing opening balances stay unknown, and negative balances stay visible. No automatic annual replenishment/proration or hard balance enforcement is introduced. Editing the employee's opening field corrects the original opening amount, not today's remaining balance; it does not reset the date. The UI labels this explicitly. Earlier acceptance evidence above describes the superseded used/planned split.

Immediate-debit verification: 538 backend tests/package, 11 frontend tests, lint/build, Docker build/config and 27 isolated API checks passed. Browser creation of future 1–5 November leave immediately showed used 5, remaining 15 from opening 20; UI cancellation restored used 0, remaining 20. No Plan columns remained. Local synthetic evidence: ignored `runtime/audit/verify-immediate-leave-balances.ps1`, `immediate-leave-api-results.log`, `immediate-leave-created.png`. Mandatory backup preceded live MCG backend/frontend update. No migration or device operation.


Unit/API tests cover tenant references, date/overlap/duplicate rejection, cancellation, template round-trip, all-or-nothing import, HR-code mapping, status export, and Tabel/Excel `M` across month boundaries. A separate Docker PostgreSQL/backend/frontend environment with synthetic data verified create/edit/cancel, template download and filled-template multipart import, duplicate rejection, full export, and live Tabel projection. Browser checks verified sidebar/tab navigation, inline validation, responsive layout and successful form submission. Test backend/database were on an internal network with no physical-device access.
