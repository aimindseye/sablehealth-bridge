# Sable Health Bridge — contributor and coding-agent policy

Read [README.md](README.md), [Android testing methodology](docs/ANDROID_TESTING_METHODOLOGY.md) and [R11.4 acceptance](docs/R114_IMPLEMENTATION_ACCEPTANCE_2026-11-26.md) before changes. Preserve existing quality/security checks, exact toolchain, current authorization and write-safety boundaries and independent issue #10 acceptance. No merge, release, real-data write, permission grant, destructive cleanup or signing without owner authorization. The owner alone merges validated draft PRs. Follow Q25-first testing requirements; unimplemented or unobserved tests are not passes. Existing release/security/license holds remain.

## Ubuntu execution and signing migration

Follow the owner-approved Ubuntu paths and proposed build/sign separation in `docs/ANDROID_TESTING_METHODOLOGY.md`. Until implementation and security qualification, existing Mac-side build/preflight/signing rules remain in force. Never give the build identity access to signing keys or execute repository code under the signing identity. No automatic signing, merging or release publication.
