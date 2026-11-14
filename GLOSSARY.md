# WLED TV

Ambient bias lighting application for Android TV and Google TV, streaming real-time screen colors to WLED fixtures.

## Language

**AmbientStreamingEngine**:
The deterministic in-process engine coordinating video frame sampling, temporal smoothing, Widevine DRM detection, reference-counted diagnostic overrides, and transmission cadence.
_Avoid_: CaptureService, StreamingService, BackgroundEngine

**CaptureSettings**:
Global screen capture cadence, edge sampling margin, Widevine black-bar detection, and corner weighting parameters decoupled from per-fixture optical calibration.
_Avoid_: CalibrationSettings, GrabberConfig

**ConfigRepository**:
The reactive state store providing observable, thread-safe access to lighting configuration, schema migration, backup export/import, and device fleet settings.
_Avoid_: PreferencesManager, SettingsHelper, ConfigModel

**DiagnosticStreamCoordinator**:
The engine-layer coordinator running HSV test rainbows and calibration preview loops with pre-allocated buffers over `WledControllerClient` and mirroring frames to the sampling overlay.
_Avoid_: TestPatternLoop, PreviewStreamingLoop

**FixtureGeometryEngine**:
The dedicated geometry domain engine consolidating physical LED perimeter coordinates, ambient fixture sub-zone partitioning, dynamic aspect crop state (letterbox/pillarbox snapping), and UI preview canvas layout vector generation.
_Avoid_: PerimeterHelper, ZoneCalculator, LayoutEngine, RectHelper

**FleetCoordinator**:
The domain/network lifecycle coordinator (`WledFleetCoordinator`) owning shared reachability polling, atomic device toggle, removal, identification, and mDNS discovery orchestration.
_Avoid_: DeviceReachabilityCache, WledDiscovery

**PerimeterLayout**:
The physical diode arrangement specification defining LED counts per bezel, start corner, and strip winding direction.
_Avoid_: StripLayout, LedBorder, FrameConfig

**PresetProfile**:
A cohesive, named optical configuration bundling calibration curves, temporal smoothing speeds, and luminance thresholds for specific viewing moods.
_Avoid_: LightingMode, DisplayScene, ColorTheme, SettingPreset

**ScreenColorProcessor**:
The consolidated optical color processing engine executing spatial box sampling, calibration curve lookups, adaptive temporal smoothing, and internal wire-channel gamut mapping.
_Avoid_: ColorSmoothingFilter, ColorFilter, ColorMapper, ColorConverter

**WledControllerClient**:
The unified network client managing all fixture input/output, encapsulating real-time UDP frame streaming (DDP, DRGB, WARLS), reactive instance-scoped `reachabilityFlow`, and asynchronous HTTP controller state management (power, brightness, discovery).
_Avoid_: WledUdpSender, WledHttpClient, NetworkManager, SocketHelper, DeviceReachabilityCache
