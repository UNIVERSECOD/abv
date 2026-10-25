# Leave browser verification — 2026-10-25

Verified source: `07d3961`, branch `codex/leave-types`. No application code changed during this browser audit.

The actual frontend and backend Docker images were run at `http://localhost:3102` against a separate, disposable PostgreSQL database. Tests used eight synthetic employees with 20-day opening balances, paid/unpaid leave types and synthetic attendance. The database used tmpfs, the backend had no physical devices and could not reach the real ISAPI bridge. Customer data and the running installation were not used for mutations.

All interactions below used the rendered browser UI, including login, modals, employee selection, uploads, downloads and navigation. Downloaded workbooks were also parsed to verify their contents. Supplemental API/database reads confirmed failed import atomicity; they did not replace the browser interactions.

| Area | Observed result |
| --- | --- |
| Login and empty list | Login opened the dashboard. Employees without requests were absent from the leave table. |
| Creation modal | Empty submission showed field errors. Employee search inside the dropdown narrowed to the selected HR code. |
| Paid creation/edit | Inclusive October 25–27 used 3 days and left 17. Editing through October 28 used 4 and left 16. |
| Cancellation/history | Cancellation retained a rejected request, charged zero days and restored the balance to 20. A subsequent request appeared as a separate newest row. |
| Unpaid leave | Three unpaid days did not reduce the shared balance. |
| Validation | Overlapping approved dates and a request exceeding the remaining balance were rejected with inline messages. |
| Pending approval | A pending two-day paid request charged zero. Approval charged 2 and left 18. |
| Employee/type reassignment | Editing that request to another employee and an unpaid type restored the original employee's balance to 20; the new employee retained 20. |
| Filters | Month, inclusive overlapping date range, search, status and clear worked. Invalid date bounds displayed an error. |
| Leave ordering | All 12 data columns switched ascending/descending. No sorted numerically; newest reset restored creation order. |
| Type modal | Empty-field errors and the three-character abbreviation limit worked. Creating and editing a type updated its table entry and dynamic leave column. |
| Unused type deletion | The archived unused type disappeared from the active type list and the leave columns. |
| Excel template | The browser downloaded a valid workbook with required headers, two active types and all eight synthetic employees in its reference sheet. |
| Bulk Excel import | Two employees' approved two-day requests were added, each leaving 18. A file containing an unknown employee wrote no rows; a combined 22-day import against a 20-day balance also wrote no rows. |
| Full leave export | The browser-downloaded workbook contained all 11 requests present at export time, including the rejected record, with expected dates/statuses and imported rows. Later browser scenarios added a twelfth request. |
| Dashboard warning | Six employees had both attendance and approved leave. The warning showed only the first five. “Hamısına bax” opened `/reports?status=WORKING_ON_LEAVE`. |
| Daily report/filter export | Exactly employees 01–06 appeared; employee 07 (leave only) and 08 (attendance only) were excluded. The downloaded daily workbook contained those same six records and the combined status label. |
| Tabel | October showed the approved type abbreviations; cancelled-only days had no leave marker. November showed US for employee 02 on days 1–3 and employee 06 on days 5–6, and UÖ for employees 03/04 on days 15–16. The legend explained both abbreviations. |
| Tabel export | The actual browser-downloaded November workbook contained those exact day cells and both legend entries. |
| Employee profile | Employee 03 showed paid usage 2/shared remaining 18 and unpaid usage 1/“Limitsiz”. |
| Existing bulk permissions | The modal created hourly permissions for employees 07/08. Both appeared, newest first, with No 1/2. All eight data-column sort controls and newest reset worked. |
| Browser console | No error logs were captured at completion. |

The audit recorded 34 successful check groups in local `runtime/audit/browser-regression/browser-results.json`. Screenshots and inspected copies of the four downloaded workbooks are stored alongside it. These local artifacts and temporary credentials are not delivery files.

No new application defect was found in these scenarios. The browser driver required native arrow-key events after filling date inputs to commit React changes. Native confirmation handling and some download acknowledgements were delayed; final UI state and downloaded file contents were checked before counting those cases as passed. These automation delays were not treated as application failures.

This verifies the listed leave workflows and affected permission/report/Tabel paths in the same Docker images, using synthetic data. Physical terminal firmware and device writes were not tested. The earlier automated build/test audit remains documented in `CODEX_HANDOFF.md`; its suites were not rerun for this documentation-only update.
