# 02: In-Process AmbientStreamingEngine & Clock Abstraction

**What to build:** A deterministic, in-process ambient streaming engine that coordinates video frame color sampling, temporal smoothing step dispatch, multi-protocol UDP transmission, Widevine L1 DRM blackout detection, and 3-hour throughput telemetry tracking. It operates with zero allocations in the 60 FPS hot path and runs completely decoupled from Android OS service lifecycles, driven by explicit frame intake and timer ticks against a pluggable clock.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] A thin `Clock` interface is introduced with a production system implementation and a deterministic `TestClock`.
- [x] `AmbientStreamingEngine` exposes `onFrameAvailable(...)` for video buffer intake and `tickStreaming(nowMs: Long)` for transmission cadence.
- [x] Pre-allocated color byte buffers (`rawTargetBuffers` and `deviceBuffers`) are managed internally and resized only when fixture LED counts change, guaranteeing zero GC allocations in the streaming loop.
- [x] Widevine L1 DRM blackout detection state machine triggers after 5 seconds of continuous 100% black frames with active audio, decoupling the audio check via an injected query function.
- [x] Transmission rate throttles to 1500ms keepalive frames during DRM blackout, and recovers immediately when non-black video frames resume.
- [x] Suspending `blackout(sendPowerOff: Boolean)` routine transmits zeroed frame bursts and resets smoothing filter states cleanly.
- [x] An explicit `isTestingOverride` flag suppresses video frame extraction and ticker transmissions during external diagnostic tests.
- [x] The 3-hour throughput history ring buffer and per-second FPS/packet rate calculators are encapsulated within the engine.
- [x] Comprehensive JVM unit tests verify 60Hz pacing, zero allocations, DRM detection/recovery, blackout sequencing, and throughput metrics using `TestClock`.
