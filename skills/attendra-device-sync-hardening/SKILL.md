---
name: attendra-device-sync-hardening
description: Port and verify Attendra Hikvision device fixes across company variants. Use when a device is reachable by Digest ISAPI but remains offline, large event history makes manual sync time out or run concurrently, the online timestamp stays stale, or bulk FDSearch returns only some faces although users have faces on the terminal.
---

# Attendra Device Sync Hardening

Apply the fixes to the target variant's current structure rather than blindly copying files or commits. Preserve tenant filters, device mappings, raw access events, credentials, employee data, and existing face images.

## Inspect first

1. Read the target repository's `AGENTS.md`, handoff, variant README, migrations, and Git history.
2. Trace frontend → backend → ISAPI calls for device list, status, manual history sync, employee import, and face download.
3. Distinguish the backend `DeviceConfig.id`, ISAPI bridge device ID, terminal IP, HR employee ID, and terminal `employeeNo/FPID`.
4. Confirm the symptom with read-only evidence. A Digest `200` from `deviceInfo` proves connectivity; a partial FDSearch result proves the face path works but not that the list is complete.
5. Never expose passwords or contact/write to a physical terminal from tests. Obtain explicit authorization before any real device write.

## Fix online status independently of history

Treat reachability and history progress as separate state.

- In the manual sync endpoint, call the read-only device status check first.
- On success, mark the device online immediately and return without waiting for the full event backlog.
- On failure, mark it offline and return a gateway error.
- When marking online, update the successful-contact timestamp (the cursor `lastPollTime` in the canonical implementation). The frontend requires both `online=true` and a recent timestamp; updating only the boolean still renders the device offline.
- Do not reset serial numbers, event times, assignments, or raw events.
- Run history ingestion in the background. Tell the UI that synchronization started, not that it already completed.

Canonical paths in the ABV layout:

- `isapi/.../controller/DeviceController.java`
- `isapi/.../service/DeviceCursorService.java`
- `frontend/src/pages/DevicesPage.tsx`
- `frontend/src/utils/deviceOnline.ts`

## Serialize history synchronization per device

Prevent the scheduler and repeated UI clicks from opening multiple searches for one terminal.

- Keep one lock per ISAPI device ID, such as a `ConcurrentHashMap<Long, ReentrantLock>`.
- Let scheduled/background work use `tryLock`; skip and log when the same device already has a running history sync.
- Release the lock in `finally` on every path.
- Enable asynchronous execution through the application's normal Spring mechanism and keep the HTTP request short.
- Mark online after a successful read-only status response, before slow pagination.
- Do not mark a reachable device offline merely because history pagination is slow or fails later. Let the next connectivity check decide reachability.
- Preserve pagination safety limits, cursor advancement rules, de-duplication, raw-event persistence, and ingestion failure handling.

Canonical path: `isapi/.../scheduler/AcsEventHistoryPoller.java`.

## Import every face, not only the bulk index

Do not treat the bulk FDSearch index as authoritative. Some DS-K1T firmware returns a small partial list even when many users have registered faces.

1. Read users through the existing `UserInfo/Search` flow.
2. Read the bulk face index as an optimization only.
3. For every imported, non-conflicting employee linked to that device and lacking a local face image, request the face by that terminal's exact `employeeNo/FPID`.
4. Keep the existing per-person fallbacks: use a returned face URL when available, then the direct employee-number download path.
5. Save a face only to the employee resolved from that same device-scoped identity.
6. Never overwrite an existing local face during import.
7. Count a person as “no face” only after the individual lookup returns not found. Distinguish not-found from transport/decode failure.
8. Continue individual lookup even when the bulk index is empty.
9. Report bulk count, individually discovered count, synced count, existing count, no-match count, and failures separately where the DTO permits.

Canonical paths:

- `backend/.../service/HikDeviceUserImportService.java`
- `isapi/.../service/DeviceUserService.java`
- `isapi/.../device/client/IsapiClient.java`

This import path must remain read-only toward the terminal. Do not reuse the “employee sync” action, which sends local employees/faces to the device.

## Required regression tests

Add or adapt focused tests for:

- reachable manual sync marks online and starts background history work;
- unreachable manual sync marks offline and does not start background work;
- `markOnline` refreshes the successful-contact timestamp while preserving serial/event cursors;
- history failure after a successful status check does not immediately flip the device offline;
- a second history request for the same device is skipped while one is running;
- a face missing from the bulk index is found by individual `employeeNo/FPID` and saved;
- an existing local face is not overwritten;
- individual not-found remains a no-match, while download/decode errors remain failures;
- tenant and device-scope protections remain intact.

Run focused tests first, then the target variant's full relevant backend, ISAPI, frontend, and Compose checks. Report unavailable runtimes honestly. Never claim physical-device verification from mocks or builds.

## Delivery checklist

- Review the diff for unrelated files and secrets.
- Do not edit an already-applied migration; these fixes normally require no migration.
- Do not stage `.env`, generated output, customer data, or unrelated variant changes.
- Deploy only the changed services: typically ISAPI/frontend for status changes and backend for face-import changes.
- After deployment, use read-only logs to verify one search per device, a fresh online timestamp, and individual face discovery. Do not repeatedly click sync while a job is active.
