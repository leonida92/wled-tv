# 03: AmbientStreamingEngine Three-Stage Zero-Allocation Pipeline

**What to build:** Implement a clean, decoupled three-stage memory pipeline in `AmbientStreamingEngine` where video frame acquisition samples into raw RGB buffers, the 60Hz ticker applies temporal smoothing into canonical RGB buffers (serving distortion-free UI previews and HUD overlays), and hardware wire encoding writes into pre-allocated channel buffers immediately before UDP transmission.

**Blocked by:** 01 (ScreenColorProcessor Wire Encoding and Smoothing Absorption), 02 (WledControllerClient and WledUdpSender Pure Byte Transport)

**Status:** resolved

- [x] `AmbientStreamingEngine` maintains pre-allocated `wireBuffers: HashMap<String, ByteArray>` sized to `ledCount * (if (isRgbw) 4 else 3)` alongside `rawTargetBuffers` and `deviceBuffers`.
- [x] `updateConfig(newConfig)` dynamically manages and resizes `wireBuffers` when LED counts or RGBW configuration changes.
- [x] `tickStreaming` executes:
  1. `colorProcessor.applySmoothing(dev.id, rawBuf, leds, smoothingFactor, smoothBuf)` (producing canonical RGB).
  2. `colorProcessor.encodeWireFrame(dev, smoothBuf, wireBuf)` (producing formatted wire channels).
  3. `controllerClient.sendFrame(dev, wireBuf, leds)` (transmitting wire bytes).
- [x] `liveFrameListener` and `getOverlayData()` continue reading canonical RGB from `deviceBuffers`, ensuring TV preview UI and HUD telemetry wireframes never render distorted GRB or 4-channel colors.
- [x] Zero GC allocations occur in the 60 FPS streaming loop.
- [x] Unit tests in `AmbientStreamingEngineTest` verify the three-stage pipeline, canonical preview delivery, and wire transmission with `FakeWledControllerClient`.
