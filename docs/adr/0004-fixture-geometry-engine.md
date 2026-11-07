# Fixture Geometry Engine and Canvas Layout Solvers

Consolidated physical LED coordinate math, ambient sub-zone partitioning, dynamic aspect ratio crop calculation (letterbox/pillarbox detection and snapping), and UI preview canvas layout vector generation into a dedicated, pure-Kotlin `FixtureGeometryEngine`.

Domain models (`WledDevice.customRect`, `DeviceOverlaySnapshot.zones`) and geometry calculations are decoupled from the Android framework via a zero-dependency immutable `NormalizedRect` primitive, enabling 100% JVM unit testability without Android runtime stubs or Robolectric.

`ScreenColorProcessor` is stripped of all fixture and device geometry dependencies, functioning as a pure optical engine (optical probing, LUTs, chroma sampling, temporal smoothing, wire encoding). Dynamic letterbox/pillarbox detection feeds raw border readings into `FixtureGeometryEngine`, which manages the asymmetric temporal hysteresis filter (15 frames to expand picture, 30 frames to shrink) and aspect preset snapping.

`TvPerimeterPreviewView` and `ZoneCanvasView` are converted into declarative canvas rendering adapters:
1. `FixtureGeometryEngine.calculatePreviewLayout` pre-solves TV chassis bounds, screen bezels, and individual diode display vectors (`TvPreviewLayout`) on view resize or device fleet changes.
2. `FixtureGeometryEngine.calculateZoneCanvasLayout` pre-solves shaded letterbox rects, sampling wireframes, perimeter border lines, and aspect badge coordinates (`ZoneCanvasLayout`).

`FixtureGeometryEngine` maintains internal mutation-driven zone caches per device ID, invalidating only upon configuration mutations or detected crop transitions to guarantee zero heap allocations during steady 60Hz ambient streaming.
