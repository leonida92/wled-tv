# Coding Standards

Judgement calls and conventions for reviewing changes in `wled-tv`.

## Reactive State & Threading

- **Non-blocking UI:** Mutate state asynchronously using `lifecycleScope.launch { configRepo.updateConfig { current -> ... } }`. Never block the Android main thread with `runBlocking`.
- **Functional state updates:** Always modify the incoming lambda parameter `current` (e.g. `current.updateDevice(...)` or `current.copy(...)`). Never overwrite state with stale activity-local properties, as this clobbers concurrent updates.
- **Throttled side-effects:** Network calls triggered by state emissions (e.g. HTTP wake commands in `AmbientCaptureService`) must check whether relevant target endpoint parameters (IP, port, enabled state, device count) actually changed before firing. Do not fire network requests on purely optical or calibration slider adjustments.

## Architecture & Seams

- **Service as thin adapter:** `AmbientCaptureService` is an Android OS adapter for hardware capture (`MediaProjection`, `VirtualDisplay`, `ImageReader`) and foreground service lifecycle. Core color processing, streaming cadence, Widevine DRM detection, and telemetry belong in `AmbientStreamingEngine`.
- **Encapsulated snapshots:** Views (such as `SamplingOverlayView`) must not navigate across collaborators (`engine.colorProcessor...`). Expose and consume unified snapshot models (e.g. `engine.getOverlaySnapshot()`).
- **Deterministic timing:** Time-sensitive modules must accept a `Clock` interface so fast-forwarding intervals can be verified in JVM unit tests without `Thread.sleep`.
