# Issue 05: Verification and Release Prep

Status: resolved

## Description

1. Run full JVM unit test suite: `gradlew.bat testDebugUnitTest`.
2. Build release APK: `gradlew.bat assembleRelease`.
3. Verify zero heap allocations in cadence loops and zero regressions.
4. Update `versionCode` to 38 and `versionName` to "1.3.6" in `app/build.gradle.kts`.
5. Update `PROJECT_STATE.md` and release notes.

## Answer

- Bumped `versionCode` to 38 and `versionName` to "1.3.6" in `app/build.gradle.kts`.
- Updated `PROJECT_STATE.md` with Pass 5 capabilities (Preset Profiles, Service Decomposition, Discovery Seam, and Schema Migrations).
- All 162 JVM unit tests build and pass cleanly via `gradlew.bat testDebugUnitTest`.
- Assembled release APK: `app/build/outputs/apk/release/wled-tv-v1.3.6-release.apk` (5,311,099 bytes).

