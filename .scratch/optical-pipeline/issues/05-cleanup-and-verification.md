# 05: Deprecation Cleanup and End-to-End Verification

**What to build:** Safely delete obsolete standalone `ColorSmoothingFilter.kt` and `ColorSmoothingFilterTest.kt` files, verify that all references have migrated to `ScreenColorProcessor`, and run the entire JVM test suite to guarantee 100% green tests in ~8 seconds.

**Blocked by:** 03 (AmbientStreamingEngine Three-Stage Zero-Allocation Pipeline), 04 (UI Test Pattern and Calibration Preview Migration)

**Status:** resolved

- [x] Obsolete standalone `ColorSmoothingFilter.kt` and `ColorSmoothingFilterTest.kt` files are deleted after confirming all functionality and tests are absorbed into `ScreenColorProcessor`.
- [x] No remaining files in the codebase import or reference `ColorSmoothingFilter`.
- [x] All JVM unit tests pass 100% green via `cmd /c "gradlew.bat testDebugUnitTest"` in ~8 seconds without emulators.
- [x] Clean architectural boundaries verified: `ScreenColorProcessor` owns all optical math, `WledControllerClient` owns pure byte transport, and `AmbientStreamingEngine` coordinates the three-stage decoupled pipeline.
