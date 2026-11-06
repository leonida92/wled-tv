# 03: Slim AmbientCaptureService Adapter

**What to build:** A refactored, lightweight `AmbientCaptureService` that acts solely as an operating system adapter connecting Android hardware APIs (`MediaProjection`, `VirtualDisplay`, `ImageReader`, `NotificationManager`, locks) to the in-process `AmbientStreamingEngine`. It collects configuration updates from `ConfigRepository`, schedules the 60Hz ticker loop, and exposes the active engine instance as an observable stream for safe UI observation.

**Blocked by:** 01: Reactive ConfigRepository, 02: In-Process AmbientStreamingEngine & Clock Abstraction

**Status:** resolved

- [x] `AmbientCaptureService` shrinks to a focused adapter (~150-200 lines) stripped of buffer management, DRM state machines, and telemetry calculations.
- [x] The service collects `ConfigRepository.configFlow` and forwards new configuration snapshots synchronously to `engine.updateConfig(config)`.
- [x] Hardware frames from `ImageReader` are forwarded directly to `engine.onFrameAvailable(...)`.
- [x] The background handler executes a steady ticker runnable that calls `engine.tickStreaming(...)` at the configured frame rate.
- [x] System standby, daydreaming, and screen-off broadcasts trigger `engine.blackout()`.
- [x] The service exposes `val activeEngine: StateFlow<AmbientStreamingEngine?>`, setting the instance when capture starts and clearing it to `null` on stop.
