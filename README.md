# WLED TV

[![License: CC BY-NC 4.0](https://img.shields.io/badge/License-CC%20BY--NC%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc/4.0/)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue.svg)](https://android.com/tv/)
[![WLED Compatible](https://img.shields.io/badge/WLED-UDP%20DRGB%20Realtime-success.svg)](https://kno.wled.ge/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)

**WLED TV** is a native, ultra-low-latency ambient bias lighting application designed specifically for **Google TV Streamer**, **Chromecast with Google TV**, and **Android TV** devices.

It captures on-screen video in real time, extracts rich chroma-weighted edge colors, and streams them directly over your local Wi-Fi/Ethernet network to any [WLED](https://kno.wled.ge/) addressable LED strip using low-overhead **UDP DRGB** packets (no Raspberry Pi, capture card, or Hyperion server required).

---

## Features

- **Optimized for TV and Remote Navigation**: Full 10-foot Leanback UI with smooth D-pad remote navigation, visual focus feedback, and overscan protection.
- **Interactive 4-Edge Perimeter Calibration**:
  - Live on-screen zone canvas showing real-time LED sampling rectangles.
  - Independently adjust **Top**, **Bottom**, **Left**, and **Right** border insets (0% - 40%) directly with the TV remote.
- **Movie Aspect Ratio Presets and Squeezed Side Zones**:
  - One-click presets for **16:9 Fullscreen**, **2.39:1 Cinemascope**, **2.35:1 Widescreen**, **2.00:1 Univisium (Netflix)**, **1.85:1 Theatrical Flat**, and **4:3 Pillarbox**.
  - **No Black Bar Sampling**: Left and Right LED zones are automatically squeezed into the active movie window so black letterbox bars are never sampled.
- **Live Color and Gain Calibration**:
  - **On-Screen Reference Test Colors**: Illuminate your TV edges with solid **6500K Pure White**, **3200K Warm White**, **Red**, **Green**, **Blue**, **Cyan**, **Magenta**, or **Yellow** to visually match TV screen colors against LED wall reflections.
  - **Chroma-Weighted Sampling**: Boosts vibrant foreground colors over washed-out backgrounds.
  - **Black Level Cutoff Threshold (0 - 50)**: Turn LEDs completely OFF (R=0, G=0, B=0) in dark scenes instead of emitting faint gray glow.
  - **Individual RGB Gains**: Fine-tune Red, Green, and Blue gain multipliers for accurate white balance.
  - **Temporal Smoothing (EMA Filter)**: Fluid, flicker-free LED color transitions.
- **Dedicated System and Connection Settings**:
  - Direct WLED IP configuration with on-screen live connection status pill.
  - One-click local mDNS LAN auto-scan for WLED controllers.
  - Selectable frame rate: **60 FPS** (Ultra Smooth), **30 FPS** (Balanced), **15 FPS** (Power Saver).
  - Auto-start on TV boot option.
- **Ultra-Low Latency and High Performance**:
  - Downsampled GPU capture buffer (320 x 180) enables sub-millisecond edge processing.
  - Direct UDP DRGB packets on port 21324 with zero intermediate proxies.
  - Uses less than 2% CPU on modern TV streaming devices.
- **HDMI CEC Standby and Instant Wake**:
  - Automatically blacks out the LED strip when the TV is put into standby via the remote.
  - Instantly resumes streaming colors on wake with zero reconnection delays or permission popups.
- **100% Private and Local**:
  - Operates entirely on your local home network (LAN).
  - Zero analytics, zero telemetry, zero cloud dependencies, no saved images.

---

## Hardware and Software Requirements

1. **Streaming Device**: Google TV Streamer, Chromecast with Google TV (4K / HD), Nvidia Shield TV, or any Android TV box running **Android 8.0+ (API 26+)**.
2. **WLED Controller**: ESP32 or ESP8266 running [WLED firmware](https://install.wled.me/) connected to your local Wi-Fi or Ethernet.
   - *Ensure **Receive UDP realtime** is enabled in WLED -> Config -> Sync Interfaces*.
3. **LED Strip**: WS2812B, SK6812, WS2815, or similar addressable RGB/RGBW strip mounted around the back perimeter of your TV.

---

## Installation and Sideloading

### Method 1: ADB (Fastest)

1. On your Google TV / Android TV:
   - Enable **Developer Options**: *Settings -> System -> About -> click 'Android TV OS build' 7 times*.
   - Enable **Network Debugging**: *Settings -> System -> Developer options -> Network debugging*.
2. Connect from your computer (PowerShell, Terminal, or Command Prompt):
   ```bash
   adb connect <TV_IP_ADDRESS>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Method 2: Sideload via USB or "Send Files to TV"
1. Install **Send Files to TV** and a file manager (e.g. **AnExplorer** or **FX File Explorer**) from the Google TV Play Store.
2. Send the compiled `.apk` to your TV and open it to install.

---

## Quick Start Guide

1. **Launch WLED TV** on your TV.
2. **Configure Connection**:
   - Open **System & Connection** to enter your WLED controller's IP address (e.g., `192.168.1.66`) or use **Scan LAN**.
3. **Configure Strip Layout**:
   - Open **Strip Geometry** and enter the LED counts for your **Top**, **Right**, **Bottom**, and **Left** edges.
   - Select your **Start Corner** (where your ESP connects to the strip) and **Direction** (Clockwise / Counter-Clockwise).
4. **Adjust Perimeter Zones and Aspect Ratios**:
   - Open **Screen Zones & Crop** to align the capture sampling boxes with your screen borders or select a movie aspect ratio preset (**2.39:1 Cinemascope**, **16:9**, etc.).
5. **Calibrate Colors**:
   - Open **Color & Calibration** to tune saturation boost (default `1.6x`), brightness, black cutoff threshold, and RGB gains using the on-screen reference test colors.
6. **Start Ambient Light**:
   - Click **START AMBIENT** on the main dashboard.
   - When Android prompts for screen capture permission, select **Start now**.
7. Enjoy real-time synchronized bias lighting with games, YouTube, Plex, Kodi, SmartTube, and local media.

---

## Tip: Permanently Hide the Android 14 Cast Indicator

On **Android 14 / Google TV Streamer**, Android displays a screen recording / cast icon in the top right corner during active `MediaProjection` sessions.

To permanently hide this icon:

```powershell
# 1. Prevent Google Play Services from resetting privacy flags
adb shell cmd device_config set_sync_disabled_for_tests persistent

# 2. Disable MediaProjection privacy indicators
adb shell cmd device_config put privacy media_projection_indicators_enabled false

# 3. Add cast and screen recording to system icon blacklist
adb shell settings put secure icon_blacklist cast,screen_record,screen_recording,recording,projection
```

*(To revert at any time, replace `false` with `true`).*

---

## Privacy and DRM Notice

- **Local Network Only**: All communication happens strictly between your TV streaming device and your WLED controller over your local LAN. No data is ever transmitted outside your network.
- **DRM Protected Video (Widevine L1)**: Commercial streaming apps (such as Netflix, Amazon Prime, and Disney+) enforce hardware DRM (`FLAG_SECURE`), which blanks the screen capture buffer for those specific video windows. Non-DRM content (YouTube, Plex, Jellyfin, Kodi, Moonlight game streaming, SmartTube, HDMI inputs, Twitch, and local videos) functions with full ambient illumination.

---

## Building from Source

```bash
# Clone repository
git clone https://github.com/leonida92/wled-tv.git
cd wled-tv

# Build Debug APK using Gradle (Requires JDK 17)
./gradlew assembleDebug

# Output APK located at:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## License

This project is licensed under the [Creative Commons Attribution-NonCommercial 4.0 International License (CC BY-NC 4.0)](LICENSE).

- **Attribution**: You must give appropriate credit, provide a link to the license, and indicate if changes were made.
- **Non-Commercial**: You may not use the material for commercial purposes.
