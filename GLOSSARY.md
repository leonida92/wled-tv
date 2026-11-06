# WLED TV

Ambient bias lighting application for Android TV and Google TV, streaming real-time screen colors to WLED fixtures.

## Language

**AmbientStreamingEngine**:
The deterministic in-process engine coordinating video frame sampling, temporal smoothing, Widevine DRM detection, and transmission cadence.
_Avoid_: CaptureService, StreamingService, BackgroundEngine

**ConfigRepository**:
The reactive state store providing observable, thread-safe access to lighting configuration and device fleet settings.
_Avoid_: PreferencesManager, SettingsHelper, ConfigModel

**WledControllerClient**:
The unified network client managing all fixture input/output, encapsulating real-time UDP frame streaming (DDP, DRGB, WARLS) and asynchronous HTTP controller state management (power, brightness, discovery).
_Avoid_: WledUdpSender, WledHttpClient, NetworkManager, SocketHelper
