# Fresh export checks — 2026-11-05

PASS: original storage JVM tests 10/10, errors/failures/skips 0; Android library assembled. Node reference boundary tests 16/16, fail/cancel/skip/todo 0.
Retained failures: missing android.useAndroidX in initial extracted build configuration; then Kotlin test class resolution in a path containing !. Final attempt used a byte-verified copy in a safe build path; no storage algorithm/assertion changes.

Host tools: Node24.19.0, JDK17, Gradle8.9, Android SDK35 where applicable. Historical product runtime remains Node24.18.0; host tests do not revalidate that binary ABI.
Logs and available JUnit XML are in verification/. Android builds used the existing workspace Gradle8.9 launcher, whose JAR is not redistributed.
Build configuration and source layout are archival extraction changes. Source hashes and byte identity are listed separately; no new phone behavior is claimed.
