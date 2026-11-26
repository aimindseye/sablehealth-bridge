# Sable Health Bridge — SB-R1

**Implementation candidate, not qualified for distribution.** This is a native
Android, manual sleep-only bridge. See the delivery README for qualification.

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
main session can be imported per action. No nap/backfill/scheduled sync in R1.

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

No background service, persistent app health cache, telemetry, Bluetooth or
additional health scopes. Network access is for Google authorization and API
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

## Research and acknowledgements

Thanks to the [Google Health API](https://developers.google.com/health/) and [Android Health Connect](https://developer.android.com/health-and-fitness/health-connect) documentation, [Sable Health](https://github.com/aimindseye/sablehealth), [FitMesh Sync](https://www.fitmesh.fit/en/integrations), [Open Health Sync](https://github.com/soma-intelligence/open-health-sync), [Open Wearables](https://github.com/the-momentum/open-wearables), [fitbit-air-mac (Raphael Hauser)](https://github.com/raphaelhauser/fitbit-air-mac), [fitbit-air (Thore Koritzius)](https://github.com/ThoreKoritzius/fitbit-air) and [Golden Gate](https://github.com/Google-Health-API/golden-gate) for platform documentation and relevant research/comparisons. These links acknowledge research, not code inclusion, affiliation, endorsement or supported direct BLE integration. See [Bridge research and evidence](docs/research/BRIDGE_RESEARCH_2026-11-26.md) and [Sable Health research archive](https://github.com/aimindseye/sablehealth/tree/main/docs/sable-health). Third-party dependency notices remain separate in [docs/third-party](docs/third-party/README.md).
