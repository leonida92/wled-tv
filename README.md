# WLED TV

[![License: CC BY-NC 4.0](https://img.shields.io/badge/License-CC%20BY--NC%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc/4.0/)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue.svg)](https://android.com/tv/)
[![WLED Compatible](https://img.shields.io/badge/WLED-UDP%20DRGB%20Realtime-success.svg)](https://kno.wled.ge/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)

**WLED TV** is a native, ultra-low-latency ambient bias lighting application designed specifically for **Google TV Streamer**, **Chromecast with Google TV**, and **Android TV** devices.

It captures on-screen video in real time, extracts rich chroma-weighted edge and ambient colors, and streams them concurrently over your local Wi-Fi/Ethernet network to multiple [WLED](https://kno.wled.ge/) addressable LED strips and fixtures using low-overhead **UDP DRGB** packets (no Raspberry Pi, capture card, or Hyperion server required).

---

## Features

- **Multi-Device Fleet Streaming**:
  - Stream ambient lighting simultaneously to multiple WLED controllers and fixtures across your room with zero latency penalty.
  - Assign distinct screen sampling zones to each device: **TV Backlight (Perimeter)**, **Left Lamp / Lightbar**, **Right Lamp / Lightbar**, **Top / Ceiling Uplight**, **Bottom / Floor Light**, **Full Screen Ambient (Flood)**, or **Custom Screen Region**.
- **Dedicated Light Setup & Device Manager**:
  - Full management of your lighting fleet: Add, Configure, Identify (blink test), Toggle ON/OFF with hardware sleep/wake, and Delete devices.
  - One-click local mDNS network auto-discovery for instant WLED detection.
- **Per-Device Color & Optical Calibration**:
  - Fine-tune every fixture independently to match its optical and physical placement:
    - **Max Brightness Capping**: Balance bright room lamps against TV backlights.
    - **RGB White Balance Gains & Gamma**: Correct warm vs cool white LED phosphors.
    - **Color Order**: Independent mapping (RGB, GRB, BGR, etc.) per controller.
    - **Transition Smoothing (EMA Filter)**: Custom transition speed per light.
    - **Saturation & Contrast Multipliers**: Boost vividness independently.
- **Dynamic Auto-Letterbox Detection**:
  - Real-time continuous black-bar detection that automatically shifts the sampling borders inward directly onto the active movie frame when watching widescreen or Cinemascope content.
- **Interactive 4-Edge Perimeter Calibration**:
  - Live on-screen zone canvas showing real-time LED sampling rectangles.
  - Independently adjust **Top**, **Bottom**, **Left**, and **Right** border insets (0% - 40%) directly with the TV remote.
- **Movie Aspect Ratio Presets**:
  - One-click presets for **16:9 Fullscreen**, **2.39:1 Cinemascope**, **2.35:1 Widescreen**, **2.00:1 Univisium (Netflix)**, **1.85:1 Theatrical Flat**, and **4:3 Pillarbox**.
- **Live Color and Gain Calibration**:
  - **On-Screen Reference Test Colors**: Illuminate your TV edges with solid **6500K Pure White**, **3200K Warm White**, **Red**, **Green**, **Blue**, **Cyan**, **Magenta**, or **Yellow** to visually match TV screen colors against LED wall reflections.
  - **Chroma-Weighted Sampling**: Boosts vibrant foreground colors over washed-out backgrounds.
  - **Black Level Cutoff Threshold (0 - 50)**: Turn LEDs completely OFF (R=0, G=0, B=0) in dark scenes instead of emitting faint gray glow.
- **16:9 Live LED Dashboard Simulation**:
  - Live 16:9 TV chassis simulation rendering individual LED color dots in real-time.
- **Ultra-Low Latency and High Performance**:
  - Downsampled GPU capture buffer (320 x 180) enables sub-millisecond edge processing.
  - Direct UDP DRGB packets on port 21324 with zero intermediate proxies.
  - Uses less than 2% CPU on modern TV streaming devices.
- **HDMI CEC Standby and Instant Wake**:
  - Automatically blacks out all LED fixtures when the TV is put into standby via the remote.
  - Instantly resumes streaming colors on wake with zero reconnection delays or permission popups.
- **100% Private and Local**:
  - Operates entirely on your local home network (LAN).
  - Zero analytics, zero telemetry, zero cloud dependencies, no saved images.

---

## Hardware and Software Requirements

1. **Streaming Device**: Google TV Streamer, Chromecast with Google TV (4K / HD), Nvidia Shield TV, or any Android TV box running **Android 8.0+ (API 26+)**.
2. **WLED Controllers**: ESP32 or ESP8266 running [WLED firmware](https://install.wled.me/) connected to your local Wi-Fi or Ethernet.
   - *Ensure **Receive UDP realtime** is enabled in WLED -> Config -> Sync Interfaces*.
3. **LED Fixtures**: Addressable strips (WS2812B, SK6812, WS2815) or ambient lamps/bulbs.

---

## Installation and Sideloading

### Method 1: ADB (Fastest)

1. On your Google TV / Android TV:
   - Enable **Developer Options**: *Settings -> System -> About -> click 'Android TV OS build' 7 times*.
   - Enable **Network Debugging**: *Settings -> System -> Developer options -> Network debugging*.
2. Connect from your computer:
   ```bash
   adb connect <TV_IP_ADDRESS>:5555
   adb install -r app-release.apk
   ```

### Method 2: Sideload via USB or "Send Files to TV"
1. Install **Send Files to TV** and a file manager from the Google TV Play Store.
2. Send `app-release.apk` to your TV and open it to install.

---

## Quick Start Guide

1. **Launch WLED TV** on your TV.
2. **Setup Lights and Devices**:
   - Open **Light Setup & Devices**.
   - Use **Scan LAN for WLED** or click **+ Add Light** to add your fixtures.
   - For your TV perimeter strip, click **Configure** -> **Configure Strip Perimeter** to set Top, Right, Bottom, and Left LED counts, Start Corner, and Wiring Direction.
   - For side lamps, uplights, or room ambient fixtures, select the appropriate **Screen Region / Role** and calibrate color, brightness, and gains.
3. **Adjust Perimeter Zones and Aspect Ratios**:
   - Open **Screen Zones & Crop** to align capture sampling boxes or enable **Auto-Detect** for real-time letterbox tracking.
4. **Start Ambient Light**:
   - Click **START AMBIENT** on the main dashboard.
   - When Android prompts for screen capture permission, select **Start now**.
5. Enjoy real-time synchronized bias lighting with games, YouTube, Plex, Kodi, SmartTube, and local media.

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

- **Local Network Only**: All communication happens strictly between your TV streaming device and your WLED controllers over your local LAN. No data is ever transmitted outside your network.
- **DRM Protected Video (Widevine L1)**: Commercial streaming apps (such as Netflix, Amazon Prime, and Disney+) enforce hardware DRM (`FLAG_SECURE`), which blanks the screen capture buffer for those specific video windows. Non-DRM content (YouTube, Plex, Jellyfin, Kodi, Moonlight game streaming, SmartTube, HDMI inputs, Twitch, and local videos) functions with full ambient illumination.

---

## Building from Source

```bash
# Clone repository
git clone https://github.com/leonida92/wled-tv.git
cd wled-tv

# Build Signed Release APK using Gradle (Requires JDK 17)
./gradlew assembleRelease

# Build Debug APK
./gradlew assembleDebug

# Output APKs located at:
# app/build/outputs/apk/release/app-release.apk
# app/build/outputs/apk/debug/app-debug.apk
```

---

## License

This project is licensed under the [Creative Commons Attribution-NonCommercial 4.0 International License (CC BY-NC 4.0)](LICENSE).

- **Attribution**: You must give appropriate credit, provide a link to the license, and indicate if changes were made.
- **Non-Commercial**: You may not use the material for commercial purposes.
