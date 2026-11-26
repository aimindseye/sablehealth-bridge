# Bridge research and evidence supplement — 2026-11-26

This record preserves engineering findings from Nov 23–26 Sable Health conversations. It does not change the SB-R1 manual import contract, grant background writes, or qualify any release. No personal data, record values, identifiers or credentials.

## Evidence and boundaries

- Physical Q25 Health Connect tests demonstrated Fitbit-origin steps and heart rate; sleep/HRV/resting HR were absent in the observed window. Permission, timezone, date-window and sync troubleshooting did not resolve sleep; export gap remained a hypothesis, not a confirmed universal Google defect.
- SB-0 demonstrated a consented Google Health API sleep read while the corresponding Health Connect session was unavailable. The endpoint was https://health.googleapis.com/v4/users/me/dataTypes/sleep/dataPoints:reconcile with https://www.googleapis.com/auth/googlehealth.sleep.readonly . [Official API](https://developers.google.com/health/) is the primary authority; no deprecated Fitbit Web API, extra scopes, secrets or private endpoints.
- SB-R1 owner-side end-to-end validation reported manual sleep import to Health Connect followed by display in Sable Health. This proves the tested path, not comprehensive sleep availability or ongoing background sync. Display accuracy is a separate Sable Health validation concern.
- Preserve exact source lineage, explicit preview, reread, bounded session selection, fully partitioned known stages, duplicate/ambiguity handling, stable identity/version and foreign-record immutability. No cross-app transaction; no blind retry. Refer to main README for exact SB-R1 behavior.
- SB-R2A investigates background read access. Read eligibility does not authorize automatic writes; background synchronization needs separate consent, scope, permission/revocation, idempotence, freshness, failure handling and actual CI/device evidence. No claim of current PR status in this record.
- Sleep tests must cover midnight/timezones/offset changes, awake intervals, gaps, unknown stages, overlaps, stale snapshots, duplicate writers, identity/version conflicts and missing data. No invented stages or unqualified totals. Sable Health handles display and cross-source reconciliation.
- Mandatory quality standard for new code: >=80% genuine new-code coverage, SonarCloud, Detekt, ktlint, Lint, security checks, synthetic fixtures and physical device validation. No real health data in CI, logs or issues.

## Direct device research: separate future route

[fitbit-air-mac](https://github.com/raphaelhauser/fitbit-air-mac) ([reviewed revision](https://github.com/raphaelhauser/fitbit-air-mac/tree/6f17660a7a09a44f75ca97c383c8753fb0de68b8)) reports standard BLE HR, battery/firmware, wrist events and haptics via Golden Gate on firmware 67.20001.253.2. Historical sync dump remains encrypted, and live activity and other endpoints require authorization. Single Golden Gate session reportedly conflicts with paired phone; no proof of Android coexistence. No replacement for official historical sleep route. No key extraction, unsupported private API use, unauthorized access or automatic settings writes. [ThoreKoritzius/fitbit-air](https://github.com/ThoreKoritzius/fitbit-air) was also reviewed as experimental mobile BLE; live HR/device/haptics reported, no historical sleep/steps solution. Neither project is adopted as a dependency. Static review only; verify license and device behavior independently.

Related research: [Sable Health archive](https://github.com/aimindseye/sablehealth/tree/main/docs/sable-health), [FitMesh integrations](https://www.fitmesh.fit/en/integrations) and [Open Health Sync](https://github.com/soma-intelligence/open-health-sync) as interoperability comparisons, [Open Wearables](https://github.com/the-momentum/open-wearables) for normalization. No drop-in Android Health Sync replacement verified. Research credits do not grant reuse rights or establish affiliation.

Keep future evidence dated, link primary sources, record scope and unknowns, and preserve owner-only merge/release decisions. No new permissions, changes to code or CI, or automatic write authorization in this documentation update.
