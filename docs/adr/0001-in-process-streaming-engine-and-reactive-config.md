# In-Process Streaming Engine and Reactive Config

Extracted the core capture orchestration, DRM state machine, and streaming cadence from `AmbientCaptureService` into an in-process, deterministic `AmbientStreamingEngine`, and replaced static service reload backdoors with a reactive `StateFlow<WledConfig>` in `ConfigRepository`.

This isolates Android framework lifecycle bindings (`MediaProjection`, `NotificationCompat`, `WakeLock`) into a thin service adapter, allowing the entire 60Hz ambient lighting engine and telemetry to be tested in standard JVM unit tests with deterministic clocks and zero Android emulator overhead.
