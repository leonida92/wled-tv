# Specification: In-Process Ambient Streaming Engine & Reactive Configuration

Status: ready-for-agent

## Problem Statement

As a user and maintainer of WLED TV, the ambient lighting capture pipeline is trapped inside an Android OS Foreground Service that mixes hardware permissions, display management, and window overlay rendering with real-time video color sampling, 60Hz UDP frame streaming, Widevine DRM blackout detection, and telemetry tracking.

Because all core streaming logic is intertwined with Android system services (`PowerManager`, `DisplayManager`, `WindowManager`, `MediaProjectionManager`, and `NotificationManager`), the application's most critical value proposition cannot be tested with automated JVM unit tests. Furthermore, when users modify lighting settings in any of the application's 8 configuration screens, the UI components bypass structured data flows and communicate via a global static backdoor method on the service. If the background service is stopped, restarting, or transitioning states, configuration updates are silently lost or result in race conditions.

## Solution

Decouple the real-time ambient lighting pipeline from the Android OS service lifecycle by introducing a dedicated, deterministic in-process `AmbientStreamingEngine` and a reactive `ConfigRepository`.

The `AmbientStreamingEngine` runs as a pure Kotlin module driven deterministically by explicit frame inputs and timer ticks, allowing its 60Hz transmission cadence, zero-allocation buffer pooling, Widevine DRM detection, and 3-hour throughput history to be verified thoroughly in fast, flake-free JVM tests using controllable clocks.

The `ConfigRepository` provides an observable, thread-safe configuration stream backed by asynchronous disk persistence. When a user updates calibration or device settings from any screen, the change propagates reactively across the entire application, eliminating all static service backdoors and preventing lost settings.

## User Stories

1. As a TV viewer, I want ambient bias lighting to track on-screen video smoothly at 60 FPS without frame drops, so that my viewing experience is immersive and fluid.
2. As a TV viewer, I want ambient lights to black out automatically when commercial streaming services enforce Widevine L1 DRM, so that my room does not flicker with invalid or frozen colors.
3. As a TV viewer, I want ambient lights to resume streaming automatically when I exit DRM content and resume watching standard video, so that I do not have to restart the application manually.
4. As a TV viewer, I want my lights to turn off immediately when the TV enters standby, daydream, or ambient mode, so that LEDs do not stay illuminated while the display is sleeping.
5. As a TV viewer, I want ambient lights to wake up and resume illumination instantly when the TV wakes from sleep, without reconnection delays or prompt popups.
6. As a user adjusting color calibration, I want real-time preview canvases to display current fixture colors, so that I can calibrate white balance and gains visually.
7. As a user configuring lighting parameters, I want changes made in any settings screen to take effect immediately without requiring an application restart, so that calibration feedback is instant.
8. As a user modifying light strips, I want newly added or reconfigured devices to receive streaming frames immediately without dropped packets, so that multi-device setups work reliably.
9. As a user running test patterns or corner finder diagnostics, I want ambient video streaming to pause cleanly during the test, so that diagnostic colors are not interrupted by incoming video frames.
10. As a user finishing diagnostic tests, I want normal ambient lighting to resume seamlessly, so that I do not need to toggle power off and on.
11. As a user viewing system statistics, I want accurate FPS, UDP packets-per-second, and 3-hour historical telemetry graphs, so that I can diagnose local network health.
12. As a developer, I want the core 60Hz ambient capture and streaming logic to run in pure JVM unit tests without Android OS emulators, so that test suites run in milliseconds.
13. As a developer, I want to simulate Widevine DRM blackouts and audio dropouts deterministically using virtual clocks, so that edge-case regressions can be caught before deployment.
14. As a developer, I want buffer memory allocations in the 60 FPS hot path to be strictly zero, so that garbage collection pauses never cause frame stutters on resource-constrained TV streaming dongles.
15. As a developer, I want configuration state to be immutable and observable via standard reactive streams, so that race conditions between UI activities and the streaming engine are architecturally impossible.
16. As a maintainer, I want the Android foreground service to act purely as a thin operating system adapter, so that changes to OS permissions or notifications do not destabilize the lighting engine.

## Implementation Decisions

### Modules Built and Modified

- **`ConfigRepository`**:
  - Replaces the legacy passive repository.
  - Exposes an observable state stream of configuration snapshots and an immediate snapshot accessor.
  - Exposes a suspending atomic mutating method accepting a functional transformation of the configuration.
  - Maintains the existing storage schema and JSON structure to ensure complete backward compatibility with existing user configurations and legacy single-device setups.
  - Executes disk serialization and writes on an asynchronous background I/O dispatcher while immediately updating the in-memory state stream.
  - Eliminates all static service references and manual reload signals across all consuming activities.

- **`AmbientStreamingEngine`**:
  - Encapsulates all real-time ambient lighting coordination: frame reception, spatial color extraction, temporal smoothing step dispatch, multi-protocol UDP transmission, Widevine DRM detection, standby blackout sequencing, and throughput metrics tracking.
  - Completely thread-agnostic and deterministic: exposes no internal background threads or coroutine timers. Driven from outside via explicit frame intake and tick scheduling methods.
  - Accepts a pluggable clock interface providing current time and uptime, allowing tests to control timing intervals deterministically.
  - Accepts a pluggable audio activity query function to decouple Widevine DRM audio checks from platform audio management APIs.
  - Owns and manages pre-allocated raw and smoothed color byte buffers for each active fixture. Resizes buffers only when a device's LED count changes or when devices are added/removed, ensuring zero memory allocation in the streaming loop.
  - Encapsulates the 5-second black frame countdown and 1500ms keepalive throttling for Widevine DRM blackout handling.
  - Houses the 3-hour throughput history ring buffer and per-device packet counters, calculating FPS and packets-per-second on interval boundaries.
  - Encapsulates a suspending blackout routine that writes zeroed color bytes, transmits a rapid burst of black UDP datagrams, and optionally issues HTTP power-off requests.
  - Exposes a testing override property to temporarily suppress video frame extraction and ticker transmissions during external diagnostics.

- **`AmbientCaptureService`**:
  - Retained as a thin Android Service adapter (~150 lines).
  - Handles Android OS lifecycle: foreground service notification management, media projection token binding, virtual display creation, image reader listener wiring, wake/wifi locks, and system intent broadcast reception (screen off, dreaming, network recovery).
  - Collects the configuration stream from `ConfigRepository` and forwards new configurations synchronously to the engine.
  - Passes incoming image buffers from the image reader directly to the engine's frame intake method.
  - Schedules a recurring timer calling the engine's streaming tick method at the configured frame rate.
  - Exposes an observable stream of the currently active engine instance, allowing UI activities and overlay views to bind and unbind listeners lifecycle-safely without static references or memory leaks.

- **UI Activities**:
  - All 8 configuration screens (`MainActivity`, `CalibrationActivity`, `DeviceManagerActivity`, `EditDeviceActivity`, `SystemSettingsActivity`, `ZoneEditorActivity`, `CornerFinderActivity`, `SettingsActivity`) updated to obtain `ConfigRepository` through application context.
  - Mutate settings via the repository's atomic update method instead of saving and calling static reload methods.
  - `MainActivity` and floating overlay views observe the active engine stream to register live preview and throughput listeners during their active lifecycles.

## Testing Decisions

### What Makes a Good Test

Tests must verify external behavioral contracts and observable outcomes rather than internal private variables:
- In `AmbientStreamingEngine`, tests feed input video byte buffers or trigger timer ticks, and observe transmitted UDP datagram counts, throughput metrics, or DRM state changes.
- Tests must never rely on `Thread.sleep` or real wall-clock delays; all timing intervals (60Hz pacing, 5s DRM blackout, 1s telemetry updates) must be advanced explicitly through the injected clock.
- In `ConfigRepository`, tests verify that emitting mutations updates the configuration stream atomically, that subscribers receive updates, and that serialized configurations accurately preserve device fleets and calibration data across reloads.

### Modules Tested

1. **`AmbientStreamingEngineTest`**:
   - Verification of 60Hz tick cadence and correct frame rate regulation.
   - Verification of zero buffer allocations across repeated frames.
   - Verification of Widevine L1 DRM blackout trigger: transition to DRM state after 5 seconds of black frames with active audio, and recovery when video resumes.
   - Verification of keepalive frame throttling during DRM mode.
   - Verification of blackout sequence: transmission of zeroed frames and filter state reset.
   - Verification of testing override flag: suppression of streaming when enabled.
   - Verification of throughput calculation and 3-hour history ring buffer behavior.

2. **`ConfigRepositoryTest`**:
   - Verification of initial configuration loading and legacy schema migration.
   - Verification of atomic configuration updates via functional transforms.
   - Verification of reactive `StateFlow` emission to multiple concurrent collectors.
   - Verification of device list modifications (adding, updating, deleting devices).

### Prior Art

- `ScreenColorProcessorTest`: demonstrates synthetic byte buffer generation and color assertions without Android framework dependencies.
- `WledUdpSenderTest`: demonstrates verifying UDP packet headers and payload encoding against simulated sockets.
- `ThroughputStatsTest`: demonstrates validating packet and frame counter math.

## Out of Scope

- Modifying the underlying UDP packet binary formats (DDP, DRGB, WARLS).
- Modifying optical color processing algorithms (gamma curves, saturation formulas, letterbox detection math).
- Rewriting Android TV UI layouts, navigation styling, or XML views.
- Replacing the Android `MediaProjection` or `VirtualDisplay` capture mechanisms.

## Further Notes

This refactor establishes the core architectural seam of the application, fulfilling ADR-0001. Once completed, subsequent refactors to deepen device communication (unifying UDP and HTTP into a single client) and optical rendering (absorbing color filters) will connect directly into `AmbientStreamingEngine` without touching Android OS service code.
