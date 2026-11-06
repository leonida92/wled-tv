# 05: Delete Legacy Static Backdoors & Verify Integration

**What to build:** Removal of all legacy static companion fields, listeners, and backdoor reload methods from `AmbientCaptureService`, followed by full project compilation, execution of test suites, and end-to-end verification of the refactored architecture.

**Blocked by:** 04: UI Activity & Overlay Migration

**Status:** resolved

- [x] All static listener variables (`liveFrameListener`, `stateListener`, `throughputListener`), static flags (`isTestingOverride`, `isDrmActive`), and static instance pointers (`currentServiceInstance`) are removed from `AmbientCaptureService.Companion`.
- [x] Static history helper methods (`recordHistoryPoint`, `getHistorySnapshot`, `clearHistory`) are removed in favor of engine methods.
- [x] The legacy `PreferencesRepository` is removed or replaced by `ConfigRepository`.
- [x] Full Android project compiles cleanly (`./gradlew compileDebugKotlin` / `./gradlew assembleDebug`).
- [x] All JVM unit test suites (`./gradlew test`) pass with 100% success.
