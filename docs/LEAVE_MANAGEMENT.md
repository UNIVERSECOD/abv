# İcazə / Məzuniyyət

The existing `/permissions` route now has **İcazələr** and **Məzuniyyətlər** tabs. Hourly/full-day permissions retain their existing API and calculation rules. Leave requests use the existing `leave_requests` and `leave_types` tables; no migration or stored attendance rewrite is required.

## Leave management

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

Endpoints are under `/api/leaves`: existing GET/POST, new `PUT /{id}`, existing `PUT /{id}/status`, plus `GET /template`, `GET /export`, and multipart `POST /import` (`file`). Downloads are XLSX. No device operations are involved.

## Verification

### Automatic opening-balance projection (2026-10-19)

`GET /api/annual-leave/balances` is HR-only and tenant-scoped. V043 preserves the existing employee `annual_leave_balance` as the opening balance and adds its Baku activation date. Only approved `ANNUAL` requests on/after that date debit it, including already-entered future requests. Past days before activation are never debited again. New employees receive their own creation-date anchor. Existing annual-leave ledger rows are left intact; the new opening projection does not reinterpret their manual historical usage.

Used includes today; planned is after today. Remaining = opening minus used; available = remaining minus planned. Both dates are inclusive and calendar days are used, including weekends; holiday integration/exclusion is deferred by user request. Overlapping legacy requests count each date once. Cancellation, editing, deletion and atomic Excel imports are reflected on the next read without synchronization writes. Missing opening balances stay unknown, and negative balances stay visible. No automatic annual replenishment/proration or hard balance enforcement is introduced. Editing the employee's opening field corrects the original opening amount, not today's remaining balance; it does not reset the date. The UI labels this explicitly.


Unit/API tests cover tenant references, date/overlap/duplicate rejection, cancellation, template round-trip, all-or-nothing import, HR-code mapping, status export, and Tabel/Excel `M` across month boundaries. A separate Docker PostgreSQL/backend/frontend environment with synthetic data verified create/edit/cancel, template download and filled-template multipart import, duplicate rejection, full export, and live Tabel projection. Browser checks verified sidebar/tab navigation, inline validation, responsive layout and successful form submission. Test backend/database were on an internal network with no physical-device access.
