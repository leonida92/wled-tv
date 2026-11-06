# 05: Enforce Internal Encapsulation & Full JVM Verification

**What to build:** Mark `WledUdpSender` and `WledHttpClient` as `internal` to prevent external layers from bypassing the architectural seam, verify zero socket leakage across the application, and execute the full test suite to guarantee 100% green tests.

**Blocked by:** 02 (Connect AmbientStreamingEngine & Service Adapter to WledControllerClient), 03 (Migrate Display & Calibration Activities to Shared Client), 04 (Migrate Device Management Activities & Domain Flashing)

**Status:** resolved

- [x] `WledUdpSender` and `WledHttpClient` are marked `internal` in package `com.wled.tv.network`.
- [x] No class outside `com.wled.tv.network` directly instantiates or imports `WledUdpSender` or `WledHttpClient` (verified via codebase search).
- [x] `cmd /c "gradlew.bat testDebugUnitTest"` runs and passes 100% of tests in the fast JVM test loop (~8-20s).
- [x] `CODING_STANDARDS.md` and documentation reflect the unified controller client architecture.
