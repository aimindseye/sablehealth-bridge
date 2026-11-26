# SB-R2A R11.1 — build toolchain security and release qualification

Status: **HOLD** until actual resolution, fresh scans, CI, owner review and physical tests. Candidate only, not permission to merge, sign or distribute. Parent: 2bb55d51edc6eb34cf689a870265bbaf88fd5ed8 (PR #5). Preserve PRs #2, #3 and #5 unchanged.

## Evidence from R11

Owner local preflight: core 97.86%, app 88.57%, combined 89.87%; six CI checks passed. Release and test OSV scans reported no findings, build graph reported eleven package/advisory pairs across seven artifacts. Manifest diff only version and compile SDK metadata. Sonar screenshot was main at edef93bb, overall 85.1%, not PR new-code evidence. Build-only exposure is not proof of exploitable APK code. No personal records or identifiers published.

| Reported artifact / prior version | Advisory | Candidate correction |
|---|---|---|
| bcprov-jdk18on 1.79 | CVE-2025-14813, CVE-2026-8763, CVE-2026-0636, CVE-2026-5598, CVE-2026-13506 | 1.87 |
| bcpkix-jdk18on 1.79 | CVE-2026-5588 | 1.87; align bcutil |
| commons-lang3 3.16.0 | CVE-2025-48924 | 3.18.0 |
| guava 33.3.1-jre | CVE-2026-102554 | 33.7.2-jre |
| jdom2 2.0.6 | CVE-2021-33813 | 2.0.6.1 |
| jose4j 0.9.5 | CVE-2024-29371 | 0.9.6 |
| kotlin-gradle-plugin 2.3.21 | CVE-2026-53914 | 2.4.21; align Compose plugin |

Bouncy Castle 1.87 selected rather than 1.86 because of newer upstream fixes; test-only bcprov also upgraded from 1.86. Kotlin 2.4.21 is a stable bug-fix release with documented AGP 9.2.1 / Gradle 9.4.1 compatibility; actual Detekt, ktlint, Compose and R8 execution still mandatory. AGP, Gradle, SDKs, Health Connect alpha06, app versionCode, Android sources and permissions are unchanged. Buildscript-only overrides must be demonstrated in resolved plugin graph; do not count declarations as remediation. Guava Android stays 33.7.2-android. Disable Gradle task/configuration cache and setup-gradle cache restore in CI; local qualification uses new isolated Gradle home, no untrusted init scripts or cached task outputs.

References: https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-87/ ; https://kotlinlang.org/docs/releases.html ; https://kotlinlang.org/docs/gradle-configure-project.html ; https://nvd.nist.gov/vuln/detail/CVE-2026-53914 ; https://docs.gradle.org/current/userguide/resolution_rules.html . Upstream version selection is not proof of clean scans.

## Acceptance

- Exact PR #5 ancestry; only build scripts, cache controls, static tests and this document changed. No suppression, coverage exclusions or security-baseline edits. Existing test and security gates retained.
- Use clean dedicated Gradle user home; no cache restore. Verify wrapper and distribution checksums; actual buildscript classpath, app/core release/test and detached Robolectric, plus analysis/compiler/lint tool configurations; distinguish selected artifacts from declarations. Scan all captured scopes with current OSV; no unresolved findings (any exception requires explicit owner review, not silent ignore). Inventory does not imply complete Gradle distribution/SDK or APK SBOM. Reconcile actual selected artifact versions and origins, POM licenses, notices, packaged components and redistribution terms. Existing notice archive is historical, not complete final evidence.
- Full local Android preflight and fresh CI, actual PR-specific Sonar new-code coverage >=80% when coverable new code exists; if N/A document denominator and independently compare cumulative SB-R2A new code against main. No inferred coverage from a green badge. No weaker tests. Validate manifest unchanged relative to PR #5.
- Explicit alpha06 acceptance and caller validation review, signed Q25 exact APK verification, short and delayed read-only probes, permissions/revocation and manual import non-regression per [R11 checklist](R11_FINAL_QUALIFICATION_2026-11-26.md). No automatic writes.
- Do not merge old drafts independently. Only the final candidate may be merged after actual evidence and owner approval. Public distribution remains separately gated by license/notice obligations.

No local Android build or scan was performed by the author of this candidate; the package owner must execute the qualification. Keep exact-head evidence; any change invalidates acceptance.
