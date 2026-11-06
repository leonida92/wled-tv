# 03: Migrate Display & Calibration Activities to Shared Client

**What to build:** Refactor `MainActivity`, `CalibrationActivity`, and `CornerFinderActivity` to send test patterns and perform reachability checks through the shared `WledControllerClient` rather than instantiating private `WledUdpSender` and `WledHttpClient` instances.

**Blocked by:** 01 (Unified WledControllerClient Interface, Default Implementation & Test Double)

**Status:** ready-for-agent

- [ ] `MainActivity` consumes `WledControllerClient` for quick power toggle, test frames, and device reachability checks; removes local `udpSender` and `httpClient` fields.
- [ ] `CalibrationActivity` consumes `WledControllerClient` for calibration test frames and removes local `udpSender` field.
- [ ] `CornerFinderActivity` consumes `WledControllerClient` for corner detection test frames and removes local `udpSender` field.
- [ ] Manual `udpSender.close()` calls in `onDestroy()` across all three activities are removed (the shared client lifecycle is managed application-wide).
- [ ] UI migration unit tests pass verifying shared client calls.
