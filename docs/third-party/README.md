# SB-R1-R9 third-party dependency review

Review baseline: local root commit `9e739cbe52a5fb02d27aefa9ec53d2e8392e67be` (owner-reported). Date: 2026-11-25.

## Scope and decision

The MIT license applies to original Sable Health Bridge code, not third-party dependencies. This review covers the 122 release and 47 additional test-only coordinates supplied from the app's releaseRuntimeClasspath and debugUnitTestRuntimeClasspath. It is not an APK SBOM or a complete build-tool inventory. Source publication for CI is a separate decision from APK distribution. No APK distribution approval is given by this report.

Release: 111 direct Apache-2.0 declarations, 1 BSD-3-Clause, 7 Google Android SDK License, 3 Guava-family dependencies with inherited license declarations corroborated by external metadata. No release POM missing. Test-only: 12 Apache-2.0, 12 MIT, 3 BSD-3-Clause, 1 Unicode-3.0, 1 CDDL/GPLv2 with classpath exception, 1 EPL-1.0, 1 Bouncy Castle Licence, 16 without direct license declaration (13 lack cached POM). Test libraries are not authorized for redistribution by this review. Do not vendor test artifacts. Test-only classification is relative to the supplied two app configurations, not a guarantee for every debug or build artifact.

## Evidence

- `dependency-review.csv`: all 169 unique coordinates, declarations, parent POM references, classification and notice hashes. Unresolved test entries deliberately remain unresolved.
- `release-notice-index.csv` and `release-notices.zip`: byte-exact collected release notices, deduplicated by SHA-256; index retains original artifact and entry names. The ZIP preserves original line endings and exact bytes; extract it to inspect the notice texts. These are available evidence, not a claim of complete notices. The Google SDK notices include third-party license text and companion JSON indexes, not SDK binaries.
- Input ZIP SHA-256: 993afecd3fff8df19a66635e2fb239c2a86c53c1c55983b0d6e3d3f622f32120. Every indexed POM and notice digest matched the collected bytes. Zero reported read errors.

## Upstream references

- Google SDK terms: https://developer.android.com/studio/terms
- Google Play services open-source notices guidance: https://developers.google.com/android/guides/opensource
- Guava 33.7.2 Android metadata: https://mvnrepository.com/artifact/com.google.guava/guava/33.7.2-android
- Guava BOM metadata: https://central.sonatype.com/artifact/com.google.guava/guava-bom/33.7.2-android
- Guava FailureAccess/ListenableFuture metadata: https://maven.apache.org/maven-indexer/indexer-core/dependencies.html
- Gradle wrapper publication guidance: https://docs.gradle.org/current/userguide/gradle_wrapper.html
- Gradle wrapper Apache license: https://docs.gradle.org/8.5/release-notes.html

## Before source-only publication

Review the staged files; verify the committed Gradle wrapper JAR against the official Gradle 8.13 checksum and retain its embedded license/notice files. No SDK AARs, dependency JARs (except the verified wrapper), signing keys, OAuth JSON, health records, APKs or build caches should be published. Keep the repository MIT LICENSE unchanged. Run Gitleaks on the updated history. Do not create a release or publish an APK.

## Before any public binary distribution

1. Reconcile the final release APK's actual packaged code and resources against the resolved graph, including embedded and native components; check build-only and detached configurations separately (the Robolectric instrumented SDKs are not covered by these inventories).
2. Resolve missing license declarations and required copyright/NOTICE texts, including Guava exact parent POMs and protobuf attribution. Collected notices from source artifacts alone do not demonstrate packaging compliance.
3. Preserve applicable license/notice texts with the binary, including Google Play services embedded third-party notices. Provide accessible in-app notices, or another compliant distribution mechanism; verify final APK contents and readability. Do not add new dependencies without requalification.
4. Review applicable Google SDK/API terms, OAuth consent and privacy obligations for intended distribution; this report is not legal advice or Google approval.
5. Complete actual CI, security, coverage and signed-device qualification before any release. No public APK approval yet.
