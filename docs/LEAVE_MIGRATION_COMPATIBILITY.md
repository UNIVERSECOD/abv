# Leave migration compatibility

The merge of `codex/leave-types` into `main` encountered two released V043 migrations. They implemented different accounting models:

| Existing history | Stored employee balance | Existing request accounting |
| --- | --- | --- |
| `V043__track_leave_balance_charges.sql` on earlier main | Current remaining days | Request-level choice and the exact days already charged |
| `V043__leave_balance_opening_date.sql` plus V044 on the feature branch | Opening days and opening date | Approved calendar days under the selected type's policy |

Both V043 SQL resources are retained with their original names, versions and contents under separate `db/lineage` locations. The Flyway configuration selects the matching resource from the existing history; common migrations remain under `db/migration`. Existing Flyway history is not repaired or rewritten. Unknown V043 history is rejected rather than guessed. Fresh databases use the earlier main lineage before converging through the new additive migration.

V045 adds the missing schema and accounting metadata. It does not overwrite existing employee balance amounts, request dates/statuses, credentials or assignments. In the earlier main lineage, new metadata records which requests used the previous accounting model and the initial sum of their approved stored charges. Feature-lineage records retain zero offset and use their existing policy.

For an earlier-main employee, the effective opening balance is the original stored remaining amount plus this fixed offset. Current remaining days subtract approved legacy stored charges and approved new type-based days. Consequently:

- Upgrading preserves the current remaining balance.
- Cancelling a legacy charged request restores exactly its stored charge, including charges under a type that is now non-deductible.
- Cancelling an uncharged historical request cannot create credit.
- New requests use the configured leave type. Editing or reapproving a legacy request converts it to that policy after validation.
- An unrelated employee edit preserves the stored amount and offset. An explicit opening-balance change establishes the new opening value.
- Legacy accounting flags and charged amounts are response-only API fields; callers cannot forge old charges.

Before deployment, verify fresh installation and both upgrade paths using disposable databases with real Flyway histories. Check original migration checksums, original customer columns, balances, cancellation, approval, employee reassignment and tenant boundaries. Keep both databases, photos, configuration and previous application images backed up before recreating the application containers. Never delete data volumes or run Flyway repair to resolve this version collision.

The 2026-10-26 merge passed these three actual-image startup paths, 14 compatibility API flow groups and second-start validation of both upgraded histories. The original columns, raw balances and historical Flyway entries were preserved. Focused backend tests (103), the full backend clean package (585 tests), frontend tests (57), lint/build and both Docker builds also passed. Isolated evidence is retained locally under `runtime/audit/main-merge-verification/`; it is not delivery data.
