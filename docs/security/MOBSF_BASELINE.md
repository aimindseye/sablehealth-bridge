# SB-R1-R10B — proposed source-CI ratchet (release HOLD)

This is an engineering review candidate, not a security waiver or signed/public APK release approval. Owner review of the exact baseline is required before merging. Source publication is already in place. The baseline deliberately retains all eight observed findings (one error, one implicit warning, six notes). No findings are suppressed, and any addition, removal, severity/message/location/snippet change or scanner identity drift fails the ratchet; re-review is required even after a fix. The scanner still covers the entire checkout and must succeed. The scanner's `--no-fail` option permits reporting findings, not passing the CI gate: the following policy checks scanner outcome, SARIF invocation success, exact findings, frozen source evidence and effective SDK identity from an assembled release APK. The workflow uploads raw evidence even if validation fails.

## Evidence

PR 1 head: `76fb38640b1cb700ed2d766e15ab30acee05796b`; Security run `45705958338`; artifact `15065970288`. The initial and retry reports agree on the eight findings; the fixture is the unmodified PR report. The reviewed source manifest and test fixture are pinned by SHA-256. SDK mitigation is conditional on the actual unsigned release APK's package, min SDK 30 and target SDK 36. The APK is not distributed by this workflow. SDK checks do not establish every manifest or binary security property.

## Dispositions (proposed, not owner-approved)

- `android_kotlin_hardcoded`: stale-preview test fixture, not a real credential. Exact file pinned.
- `android_task_hijacking2`: source-scanner assumes a target below 29; actual APK SDK checks required. No claim about unrelated task-hijacking vulnerabilities.
- `android_safetynet`: deprecated API; avoid adding it. Evaluate an appropriate attestation requirement only if an architecture change needs it.
- `android_root_detection`: preserve custom ROM compatibility; document root-compromise risk.
- `android_ssl_pinning`: standard platform HTTPS validation; pinning tradeoffs remain an explicit review decision.
- `android_certificate_transparency`: compatibility review outstanding.
- `android_prevent_screenshot`: privacy protection and UX decision outstanding.
- `android_tapjacking`: sensitive-write protection outstanding.

The source ratchet is not a clean-scan claim. Tapjacking and screenshot protection remain planned implementation work, requiring regression coverage and device qualification; other architectural findings require review. Maintain a separate release hold pending these decisions, CodeQL alerts review, signed APK and live device checks, and binary-license reconciliation. No change to Kotlin, Gradle dependencies, permissions or coverage thresholds is made here.

## SonarCloud

The new Python checker is included in genuine branch/line coverage measurement through pinned CI-only coverage.py 7.13.3. No source exclusions or quality-gate threshold changes. Existing Kotlin reports are untouched. The main-branch quality gate remains independently required.

## SB-R2A-R10 — exact manifest reconciliation (proposed source-CI only)

PR 2 head `83d6e17530b39a4bf96ed8e6f2d5d917cfafec5c`; Security run `45758712819`, attempt 2, MobSF job `139901847091`, artifact `15115978884` (SHA-256 `7bc6f33293d4f328ce4ba9c51159e85b2a75d48fa543f6640cb1f496fedd03a0`). The scanner completed and the APK SDK verification passed. The gate stopped at `source evidence changed: app/src/main/AndroidManifest.xml`. All eight actual findings match the previous exact normalized multiset, with no additions, removals or severity/message/location/snippet changes. The old test fixture and checker remain unchanged.

The manifest adds Health Connect background-read permission and an exported launcher-only BackgroundProbeActivity for explicit local read-only qualification. The CI APK reports package io.github.aimindseye.sablehealthbridge, version 0.2.0-sbr2a/code 2, min SDK 30 and target SDK 36. These SDK values support the existing disposition for the scanner's target-SDK assumption; they do not prove absence of other activity or IPC vulnerabilities. The source manifest SHA-256 changes from `9579bcb7d35f13ffafa7e6108c9c6d594daa555e24e3ddefe75483d0a6006595` to `8e5fb1f3fc1035c9280ddc1da31e631770ec6afe362e12e2f6430414fb980bc5`. The test-source hash is unchanged.

Only exact manifest evidence and scan provenance are refreshed in the JSON policy. All eight findings remain visible and subject to exact matching. Owner review of this narrow source-CI reconciliation is required before application/commit. No signed or public APK approval, no acceptance of residual risks, and no permission, Kotlin, dependency, scanner, coverage or workflow changes are granted by this reconciliation. Lint reconciliation, exported HealthDataSdkService caller validation, binary/license notices, privacy metadata retention and device qualification remain open.
