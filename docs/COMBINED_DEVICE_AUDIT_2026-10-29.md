# Combined-device attendance audit — 2026-10-29

Scope: a BOTH (Giriş və çıxış) terminal used with daytime schedules, as confirmed by the user. Tests used synthetic employees and mocked terminal responses, never physical devices or the customer database.

## Corrected defects

1. Raw journal device mapping mixed backend primary keys with ISAPI bridge IDs. Collisions could display the wrong device name and direction. Mapping now uses bridge IDs exclusively.
2. Tenant journal filtering also accepted backend primary keys as bridge IDs. Unknown bridge events are now excluded even when their number equals an owned backend primary key.
3. An unclosed combined-day row could retain a directional shift's grace period after midnight in live reports. Inference now carries the combined calendar deadline and expires the latest open combined entry at midnight, preserving directional-entry shift windows.
4. Dashboard presence could likewise include a previous-day combined entry after midnight. It now uses the same combined calendar deadline.
5. A raw terminal employee number could resolve to another employee's matching HR code. Terminal identity now takes precedence; duplicate terminal numbers require a unique device assignment or area match, otherwise the name stays unresolved.

Additionally, attendance synchronization skips missing/invalid bridge IDs instead of substituting backend primary keys. No migrations or customer data rewrites are required.

## Preserved rules

- Per tenant, employee, area and Baku calendar day, BOTH takes the first scan as entry and the latest distinct later scan as exit. Equal timestamps alone do not create an exit.
- Unordered/replayed imports preserve extrema. Narrow imports preserve existing wider bounds. Manual corrections win. Area and role history remain intact.
- Flexible schedules do not derive attendance from BOTH. Midnight splits days; cross-midnight shift support was not added.
- Daily/monthly/yearly reports and Tabel use paid time after applicable break/permission deductions. Details show elapsed session duration. Overtime follows the existing after-schedule-end policy, not total time minus the daily norm.
- A terminal without direction cannot prove whether a scan was entry or exit. A second distinct scan can become an exit even if it was a quick repeat; this established first/last policy was not changed.

## Verification

- 181 focused backend tests and all 620 backend tests passed; backend package and final backend Docker image build passed.
- Frontend: 57 tests, lint and production build passed.
- ISAPI: 35 Maven tests passed. Tests used mocks, not hardware.
- Compose configuration validation passed. An isolated Docker stack used a temporary PostgreSQL filesystem, mock bridge, backend and frontend; customer containers and volumes were untouched.
- 37 API/Excel checks passed: six combined devices sharing one area/day bucket, pagination, replay, out-of-order arrivals, narrow import, concurrent synchronization, breaks, permissions, leave, rest days, late/early minutes, multiple areas, single/equal-time scans, midnight separation, manual overrides, role history, tenant filtering, identity collisions, daily/monthly/yearly reports, Tabel/MCG and exported workbook values.
- Browser login and daily report verified the synthetic corrected 09:00–17:00 row: seven paid hours, one hour early departure. API/export results agreed. The mock deliberately lacks live-device status, so its connection warning is expected.

Local ignored evidence: `build/combined-audit/` (test logs, API assertions, generated workbooks and browser screenshot). No customer data is included.

## Deployment limits

The customer Docker stack was not redeployed and physical hardware/firmware was not tested. These changes take effect after deployment. No historical customer summaries were recalculated; any historical persisted-summary refresh must be separately scoped and authorized. Existing bootstrap/licensing behavior is outside this change.
