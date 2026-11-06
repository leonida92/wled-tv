# WLED TV

Ambient bias lighting application for Android TV and Google TV, streaming real-time screen colors to WLED fixtures.

## Language

**AmbientStreamingEngine**:
The deterministic in-process engine coordinating video frame sampling, temporal smoothing, Widevine DRM detection, and transmission cadence.
_Avoid_: CaptureService, StreamingService, BackgroundEngine

**ConfigRepository**:
The reactive state store providing observable, thread-safe access to lighting configuration and device fleet settings.
_Avoid_: PreferencesManager, SettingsHelper, ConfigModel
