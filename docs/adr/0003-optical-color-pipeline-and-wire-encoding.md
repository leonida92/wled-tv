# Optical Color Pipeline Consolidation and Wire Encoding

Consolidated all optical color science (spatial box sampling, gamma/saturation LUT calibration, adaptive temporal EMA smoothing, SK6812 RGBW 4-channel gamut mapping, and channel order permutation) into `ScreenColorProcessor`.

`ColorSmoothingFilter` is absorbed as an internal implementation detail of `ScreenColorProcessor`. `AmbientStreamingEngine` maintains a three-stage decoupled pipeline with zero GC allocations in the 60 FPS loop:
1. Spatial box sampling into pre-allocated raw RGB buffers (`rawTargetBuffers`) upon video frame arrival.
2. Adaptive dual-speed temporal EMA smoothing into pre-allocated canonical RGB buffers (`deviceBuffers`) on the 60Hz ticker, serving distortion-free frames to live preview listeners (`liveFrameListener`) and telemetry HUD snapshots (`getOverlayData`).
3. Hardware wire encoding into pre-allocated channel buffers (`wireBuffers`) via `ScreenColorProcessor.encodeWireFrame` immediately before transmission.

`WledControllerClient` and `WledUdpSender` are stripped of all optical math and channel reordering logic, acting as pure byte transports. In DDP and DRGB streaming, per-pixel transposition loops are replaced with direct block memory copies (`System.arraycopy`) into UDP packet buffers, maximizing sub-millisecond throughput.
