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
