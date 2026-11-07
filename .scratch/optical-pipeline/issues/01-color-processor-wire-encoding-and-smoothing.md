# 01: ScreenColorProcessor Wire Encoding and Smoothing Absorption

**What to build:** Consolidate all optical color transformations inside `ScreenColorProcessor`, absorbing `ColorSmoothingFilter` as an internal component and exposing high-performance, zero-allocation wire encoding (`encodeWireFrame`) supporting all 6 color channel orders and SK6812 4-channel RGBW gamut extraction modes.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `ColorSmoothingFilter` is absorbed directly into `ScreenColorProcessor` as an internal implementation detail, with `ScreenColorProcessor.reset()` clearing all internal smoothing state and crop metrics.
- [x] `ScreenColorProcessor` exposes `fun encodeWireFrame(device: WledDevice, sourceRgb: ByteArray, outputWire: ByteArray): Int` and companion overload `encodeWireFrame(sourceRgb: ByteArray, ledCount: Int, colorOrder: String, isRgbw: Boolean, rgbwMode: String, outputWire: ByteArray): Int`.
- [x] Wire encoding correctly supports all 6 channel orders: `ORDER_RGB`, `ORDER_GRB`, `ORDER_BGR`, `ORDER_BRG`, `ORDER_RBG`, and `ORDER_GBR` using integer enum decoding without per-frame allocations.
- [x] For RGBW devices (`isRgbw == true`), wire encoding extracts white channel based on `rgbwMode` (`AUTO_SUBTRACT`, `AUTO_ADD`, `OFF`), accurately subtracting white in `AUTO_SUBTRACT` mode, and writing 4 channels per LED.
- [x] For RGB devices (`isRgbw == false`), wire encoding outputs 3 channels per LED reordered to the target channel layout.
- [x] `ScreenColorProcessorTest` verifies all temporal smoothing scenarios (migrated from `ColorSmoothingFilterTest`), all 6 color order permutations, and all RGBW modes in JVM tests.
