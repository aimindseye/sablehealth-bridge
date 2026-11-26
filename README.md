# Sable Health Bridge — SB-R2A

**Implementation candidate, not qualified for distribution.** This is a native Android sleep-only bridge with explicit manual imports and an
opt-in, read-only background qualification probe. See the delivery README for qualification.

## Behavior

Google Identity Services requests the single Google Health sleep read-only scope.
The application keeps the returned access token in memory only; Google Play
services manages its own authorization state. No desktop client JSON, client
secret, server auth code or refresh token is used by this app. Clear session
forgets the local token, not Google's grant. Choose the same Google account as
Fitbit Air. Authorize the installed app in the same Cloud project as SB-0.

Preview uses the Google-wearables reconciliation endpoint and selects the latest
main session in a three-local-day window. Review the date. Only finalized,
fully partitioned Awake/Light/Deep/REM sessions are eligible. Unrecognized stages,
short awakening overlays, out-of-bed intervals, ambiguous overlapping sessions,
missing record identities and incomplete results block import. Only a single
main session can be imported per action. No nap/backfill or automatic writes in SB-R2A.

Before an explicit write the app rereads the API and the overlapping Health
Connect records; a changed session or decision requires another preview.
Identical foreign records are skipped. An identical Bridge record is skipped;
a stage-only correction can replace the Bridge's own same-bounds record with
an increased client version. A shifted or conflicting session is held. If an
identical external record arrives later, a new preview can offer removal of the
Bridge's matching copy only. No foreign records are ever modified or deleted.
There is no cross-app transaction or lock: another writer can race this check.
Always review actual Health Connect records after sync. Do not retry an ambiguous
write blindly. Foreign records with different boundaries or stage segmentation
are conservatively held rather than merged. This is deliberately not a general
sleep reconciler. M1-D1 must reconcile cross-source duplicates independently.

The optional background probe uses WorkManager, requires background sleep-read
permission, and does not write or delete sleep records. WorkManager persists a
random salt and pseudonymous account-binding hash plus bounded result metadata;
no OAuth token or raw sleep payload is persisted by the probe. No persistent app
health cache, telemetry, Bluetooth or additional health-metric scopes. Network access is for Google authorization and API
reads. The app can display the sleep record locally and writes only after the
owner selects the displayed action. All stored Health Connect times are instants
with supplied UTC offsets; no unit conversion in the Bridge. Theme follows system
and screen adapts to available space; explicit appearance and device-profile
controls, multi-step setup wizard, and populated production screenshot coverage
are outstanding, not claimed as implemented.

## Qualification

See the outer package README. Source checks and core policy cases can execute
without Android. Android compile, detekt, ktlint, lint, real coverage, startup
captures, release minification, Google authorization, actual write/read-back and
CI are mandatory owner-side checks, not claimed to have passed here.

## R11 toolchain and security qualification

AGP 9.1.1, Gradle 9.3.1 (distribution SHA-256 pinned), Kotlin 2.3.21,
compile SDK 37.0, target SDK 36, min SDK 30, JDK 21. The existing audited
Gradle 8.13 wrapper launcher/JAR is retained only as the bootstrap for the
pinned distribution; do not replace it with an unverified binary. Android
uses AGP built-in Kotlin; the core JVM module retains its Kotlin JVM plugin.
Health Connect 1.2.0-alpha06 includes upstream signature validation and legacy
intent fixes. It is a prerelease under qualification, not a distribution
approval. See https://developer.android.com/jetpack/androidx/releases/health-connect.

All existing quality and security gates remain mandatory. Reconcile the actual
resolved dependency graph, notices, exported components, Lint and MobSF evidence
and complete real device testing before signing or merging. The historical
third-party inventory is not evidence for the upgraded dependency graph.
