# Coding Standards

Judgement calls and conventions for reviewing changes in `wled-tv`.
Mechanical import, layer-isolation, banned-API, and singleton rules are enforced automatically by `ArchitectureBoundaryTest` during `testDebugUnitTest`.

## Reactive State & Threading

- **Non-blocking UI & isolated drafts:** Mutate persisted state asynchronously (`lifecycleScope.launch { configRepo.updateConfig { current -> ... } }`). Keep unsaved multi-screen editor state local via Activity Result drafts (`EXTRA_DEVICE_DRAFT_JSON`), guard `configFlow` UI collectors against overwriting active drafts, and preserve drafts in `onSaveInstanceState`.
- **Main-safe suspend functions:** Wrap blocking I/O (sockets, OkHttp, file writes) in `withContext(Dispatchers.IO)` inside clients, repositories, and coordinators so `suspend` APIs are safe on `Dispatchers.Main`.
- **Functional state updates:** Always transform the incoming `current` snapshot inside `updateConfig { current -> ... }` rather than writing back stale activity fields.
- **Throttled side-effects & explicit blackout:** Network wake commands triggered by state changes must verify that endpoint fields (`ip`, `port`, `enabled`, device count) actually changed before firing. Stopping a user-initiated diagnostic preview ("Off") must invoke `stopAndBlackout` rather than leaving fixtures lit.
- **Zero-allocation cadence loops:** Streaming, diagnostic, and UI preview frame callbacks must pre-allocate and reuse byte buffers and cached zone lists (`getActiveZones`) outside per-tick loops.

## Architecture & Seams

- **Unified client & fleet lifecycle:** Route all WLED network I/O and fleet reachability/power orchestration through `WledControllerClient` and `FleetCoordinator`; query `device.wireBytesPerLed` instead of hardcoding `3`/`4` channel widths.
- **Service as thin OS adapter:** Keep `AmbientCaptureService` limited to Android capture hardware (`MediaProjection`, `VirtualDisplay`, `ImageReader`), locks/power listeners, and overlay lifecycle; keep color math, cadence, DRM detection, and diagnostic modes in `engine/`.
- **Encapsulated snapshots & pure domain primitives:** Pass unified immutable snapshots (such as `DeviceOverlaySnapshot` carrying `DeviceType`) to views; confine Android graphics types (`RectF`, `Canvas`, `Paint`) to `ui/`.
- **Deterministic timing:** Inject a `Clock` abstraction into time-sensitive components so intervals test deterministically on the JVM without `Thread.sleep`.
