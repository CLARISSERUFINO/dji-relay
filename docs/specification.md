# Specification: DJI Mini 3 Pro video and telemetry relay to the PC

Revision 4.5. It replaces the original Portuguese specification.

- Revision 2 recorded the F0 decisions.
- Revision 3 integrated the existing capture and analysis system, [`ssl-aggregator`](https://github.com/LimaBR/ssl-aggregator).
- Revision 4 records decisions D6 (repository layout) and D7 (SSL-Vision processes the drone video, always live).
- Revision 4.1: the 4K replay becomes conditional on E9. SSL-Vision runs on the same PC as the loggers and still has to be installed (V20).
- Revision 4.2: corrections from F1, checked with `javap` on 5.20.1 and against SampleCode-V5: the `Application` class, the full `SDKManagerCallback`, `KeyConnection` usage, the accessory filter, the build JDK, and the firmware-version keys (sections 7.1, 7.2, Appendix A). The PC runs Ubuntu 24.04, not 26.04 (sections 4, 6, V20).
- Revision 4.3: the phone backup becomes a phone recording started and stopped by the user (RF10, 7.10); the live stream to the PC is the primary path. Separate "Live streaming", "Phone recording" and "Drone recording (microSD)" controls (7.11, F3). NFR4 and E6 no longer require a continuous phone backup.
- Revision 4.4: results of E1 and E2 (F2): Annex B, one access unit per callback, SPS/PPS before every IDR, H.264 High at 1440×1080, SEI as side data only (7.4, V2, V4, V10, V14). RTP timestamp = `pts_ms` × 90; the live-time correction is chosen in F4 (7.5, 10.2, V3, E2). No fixed 1920×1080 assumption: SSL-Vision has no resolution requirement (4, 8.3, 9.1, 11.1, V5, V19, V21).
- Revision 4.5: F3 results. E10 moves to F4, and 10.2 step 3 becomes a decision of F4. The low-latency Wi-Fi lock is held from F3. Stream-listener watchdog after a drone reconnection (7.3). `drone_logger` video path as built: two pipelines, CSV output until F4, `video_live_NN.mkv`, caps for v4l2sink (8.3, 8.6, 9.1). v4l2loopback needs `max_buffers=4` for SSL-Vision (9.1).

API names marked *verified* were checked against DJI SampleCode-V5 and the
signatures in `dji-sdk-v5-aircraft-provided:5.20.1` (Appendix A), or against the
SSL-Vision source (Appendix B). Items marked **[VERIFY]** are still open and must
be confirmed before use. If something does not exist as described, stop and ask.

---

## 1. Context: what already exists

### 1.1 ssl-aggregator

`ssl-aggregator` is the working capture and analysis system from the previous
work: slip detection on a differential-drive robot (STM32F103 + ESP8266), with
SSL-Vision as ground truth. The drone is a new **SSL-Vision camera** for the same
pipeline, for the terrain-traversability work (Fuzzy Logic and Deep Learning
courses, PUC-Rio). The robot side is already complete and must not be duplicated.

| Already in `ssl-aggregator` | What it does |
|---|---|
| `robot_logger` | Joins the robot's multicast (`239.0.0.1:10001`). Decodes the ASCII telemetry `>Speed:<packet_id>,<f0..f6>,<led>` (100 Hz: wheel speeds, setpoints, IMU, LED state) and writes `robot.telemetry` in PostgreSQL. Reports lost packets from `packet_id` gaps |
| `vision_logger` | Joins SSL-Vision's multicast (`224.5.23.2:10006`). Writes robot poses and "balls" (the robot's LED seen as a ball) to `vision.robot_detection` / `vision.ball_detection`, with `t_capture`, `t_sent`, `frame_number` and `camera_id` |
| `libcommon` | `.env` loader (`load_dotenv`, `env_str`, `env_int`), multicast `UdpReceiver`, `now_rx()` (wall + monotonic clocks), `BlockingQueue`, Postgres `BatchWriter`, SQL formatting helpers, shutdown handler |
| `sql/`, `scripts/` | Database `robot_data` (schemas `robot`, `vision`; roles `robot_writer`, `grafana_ro`); `db_init.sh`, `export.sh` (CSV), `db_dump.sh` / `db_restore.sh` |
| `grafana/` | Capture-health dashboard, including the LED ↔ ball sync check |
| `analysis/` | Python pipeline. Stage 1 time sync: fits `t_capture ≈ a·(packet_id·0.01) + b` from LED ↔ ball edges. Stage 2: resamples the 60 Hz camera pose to the 100 Hz robot grid. Stage 3: inverse kinematics. Stage 4: per-wheel slip labels. Stage 5: models |

Facts this project relies on:

- **The robot has no RTC.** Its timeline is `packet_id × 10 ms`. `packet_id` restarts at 0 on every STM32 boot, and runs are identified by `capture_session` (the logger's start time).
- **The LED toggles every 1 s** (square wave, 2 s period). Stage 1 aligns the robot timeline to the camera time with these edges and corrects crystal drift. Analysis **aligns on camera time, not on `rx_wall`**. `rx_wall` only gives a coarse first guess, and edges are then matched within **±0.5 s**.
- **Clock convention:** every row carries `rx_wall` (`system_clock`, `TIMESTAMPTZ`) and `rx_monotonic` (`steady_clock`, seconds as a double), from `common::now_rx()` right after `recv`, plus `capture_session`.
- **Stack:** C++23 (`gnu++23`, GCC 14+), CMake, libpqxx, PostgreSQL, `.env` configuration (real environment variables take precedence), and a health line to stderr once per second.
- The robot's ESP8266 is **2.4 GHz only**, and about 1 % UDP loss over Wi-Fi is normal.

### 1.2 SSL-Vision timing (verified in its source, Appendix B)

- **`t_capture`** = `gettimeofday()` on the SSL-Vision PC at the moment its capture thread *receives* the frame from the capture module. It is not the moment the image was exposed. For the drone, it therefore includes the link and decoding latency, and its jitter.
- **`t_capture_camera`** = the time provided by the capture module. The **V4L** module uses the V4L2 buffer timestamp (in **nanoseconds**). The **Video** module (OpenCV `VideoCapture`) uses `k / fps`, counted from 0 at the start of the file, in **seconds**. `vision_logger` does not store this field today.
- The V4L module accepts the `YUYV` and `MJPEG` pixel formats. Width, height, frame rate and device index are configurable. It is compiled when `USE_V4L` is true (the default).

---

## 2. Goal

Two programs:

- **Android app** (Kotlin, on the phone attached to the RC-N1). It receives the **already-encoded** live video from DJI MSDK v5 and relays it to the PC **without decoding or re-encoding**, with low latency. It also sends per-frame metadata, telemetry, events and clock beacons. It owns the whole drone configuration (camera, radio band, gimbal) and starts and stops the **4K/60 H.265 recording on the drone's microSD**.
- **`drone_logger`** (C++23, on the PC, inside `ssl-aggregator`). It is a third logger next to `robot_logger` and `vision_logger`, with the same conventions. It receives the video, saves the original bitstream to `.mkv`, **decodes it and injects it live into SSL-Vision** through a virtual V4L2 camera, and writes frames, telemetry, events and clock beacons to PostgreSQL schema `drone`.

| Source | Format | Path into SSL-Vision | Use |
|---|---|---|---|
| Live video (O3 → app → PC) | H.264 at 30 fps; 1440×1080 at about 4 Mb/s measured in F2 (E1), checked again in video mode in F5 | **Live**: `drone_logger` → v4l2loopback → SSL-Vision V4L module (section 9.1) | Ground truth in real time, always on; monitoring; time anchor |
| microSD recording | 3840×2160, 60 fps, **H.265**, plus `.SRT` if accepted | **Replay after the flight**, conditional (only if E9 shows that live detection is not good enough): MP4 → SSL-Vision Video module (section 9.2) | Higher-resolution ground truth, with exact frame times |

In both paths SSL-Vision publishes the usual detection frames, and `vision_logger`
stores them. The analysis Stages 1–4 therefore stay as they are. They only need
the timing corrections of section 10.

DJI Fly is not used. It only offers RTMP with seconds of delay and exposes neither
the raw bitstream nor per-frame timestamps. Only one app at a time can use the
controller, so **DJI Fly must be closed** while this app runs.

---

## 3. Data-flow rule

**Data flows only from the phone to the PC.** The PC never configures or commands
the phone.

- Every setting (camera, radio band, gimbal, recording, PC address) lives in the app and is changed only through its UI.
- `drone_logger` compares what it receives with an expected profile (`.env`) and warns the user about any mismatch.
- Clock synchronisation is one-way (section 7.8).
- The PC's address is fixed (a DHCP reservation on the AP) and is entered in the app.
- Injection into SSL-Vision happens inside the PC and does not change this rule.

---

## 4. Architecture

```
 DJI Mini 3 Pro ──(DJI O3, forced to 5.8 GHz)──> RC-N1 (RC231)
   │ records 4K/60 H.265 + .SRT on microSD             │ USB
   │                                                   v
   │                    Galaxy S25 (Android 15) — App (Kotlin, Compose, MSDK 5.20.1)
   │                     ├─ RTP (RFC 6184 + RFC 8285 ext.) ─────── UDP unicast :5600 ─┐
   │                     └─ frame / telemetry / event / clock JSON ─ UDP unicast :5601 ─┤
   │                                                                                     │
   │        AP: 5 GHz ch. 36/80 MHz (phone) + 2.4 GHz (robot ESP8266), with internet     │
   │                                                                                     v
   │        PC Ubuntu 24.04 (Ethernet) — ssl-aggregator
   │         ├─ drone_logger (new)
   │         │    :5600 → appsrc → jitterbuffer → depay → parse ─┬─> video_live.mkv
   │         │                                                   └─> nvh264dec ─┬─> v4l2sink /dev/videoN ─┐
   │         │                                                                  └─> display (optional)   │
   │         │    frame assembly + :5601 messages ──> PostgreSQL schema drone                             │
   │         ├─ SSL-Vision (V4L module on /dev/videoN; + other cameras if present) <─────────────────────┘
   │         │    └─ detections ── multicast 224.5.23.2:10006 ─> vision_logger ──> schema vision
   │         ├─ robot_logger ─ 239.0.0.1:10001 ──> schema robot
   │         └─ Grafana
   │
 UGV robot (ESP8266, 2.4 GHz) ──(UDP multicast, 100 Hz)──> AP ──> PC
   └── after the flight, only if needed (F7): microSD MP4 → SSL-Vision Video module (replay) → vision_logger (source = replay)
```

Default ports (configurable): **5600** RTP video, **5601** metadata, telemetry,
events and clock beacons.

---

## 5. Requirements

### Functional

- **RF1** Connect to the Mini 3 Pro through the RC-N1 with MSDK v5 and show the connection state.
- **RF2** Receive the encoded stream with `addReceiveStreamListener` and relay it by RTP/UDP unicast, with no decoding or re-encoding.
- **RF3** Detect the codec with `StreamInfo.getMimeType()` and relay whatever arrives. H.264 uses RFC 6184. If the live stream is H.265, only the RTP packetization changes (RFC 7798).
- **RF4** Per-frame metadata: size, keyframe flag, `presentationTimeMs`, phone receive time, RTP timestamp, segment, RTP sequence range, send time, NAL types.
- **RF5** Drone telemetry (attitude, velocity, position, heights, gimbal, GPS, wind, battery, link quality), stamped with the phone clock, with the measured update rate of each key.
- **RF6** Configure the drone **only from the app UI**: camera (4K/60, H.265, manual exposure, fixed white balance, colour profile, subtitles) and O3 frequency band. Start and stop the microSD recording. Read every value back and report it.
- **RF7** Point the gimbal to −90° (nadir) from the app UI.
- **RF8** One-way clock beacons (section 7.8).
- **RF9** Live video for the pilot, without disturbing the relay. The screen stays on for the whole session.
- **RF10** Phone recording: the app saves the received bitstream and every message on the phone. It starts and stops only on the user's command, and it never interrupts the live stream.
- **RF11** `drone_logger` checks the reported configuration against the expected profile in `.env` and warns the user about any mismatch.
- **RF12** `drone_logger` writes PostgreSQL schema `drone` following the `ssl-aggregator` conventions, so `export.sh`, Grafana and the analysis can use it.
- **RF13** `drone_logger` **injects the decoded live video into SSL-Vision continuously** through a v4l2loopback device, whenever it runs (indoors and outdoors). It logs the injection time of every frame.
- **RF14** (conditional: implemented in F7 only if E9 shows that live detection is not good enough) The 4K/60 file can be replayed through SSL-Vision after the flight, and its detections are stored separately from the live ones and carry exact frame times.

### Non-functional

- **NFR1 Latency:** callback → `sendto` of the frame's last packet < 5 ms (p95). Camera → PC: target < 300 ms (p95). RTP arrival → injected into v4l2loopback: < 20 ms (p95).
- **NFR2** The SDK callback never blocks: it copies the bytes and returns.
- **NFR3** Live-frame loss < 1 % with the AP at 10–20 m.
- **NFR4** If the PC or the network disappears, the app keeps receiving and resumes sending on its own. `drone_logger` tolerates a database that is temporarily unavailable. The v4l2loopback device stays open across stream interruptions, so SSL-Vision does not need restarting.
- **NFR5** 30 continuous minutes with no memory growth and no latency growth.
- **NFR6** Robot loss (`robot_logger`'s `lost=`) stays at its baseline of about 1 % while the drone flies.

---

## 6. Hardware and radio plan

| Item | Specification |
|---|---|
| Drone | DJI Mini 3 Pro (supported by MSDK 5.20.1; check the firmware against the release notes) |
| Controller | DJI RC-N1 (RC231). The DJI RC with a built-in screen is not supported by the MSDK |
| Phone | Samsung Galaxy S25, Android 15, dedicated to this job, mobile data off |
| PC | Ubuntu 24.04, RTX 5080, **Ethernet** to the AP, fixed IP (DHCP reservation). Runs SSL-Vision, the loggers and PostgreSQL |
| Robot | ESP8266 on the **same AP, 2.4 GHz**; multicast `239.0.0.1:10001` |
| AP | Dual-band, with internet. **5 GHz: channel 36, 80 MHz, no DFS**, for the phone. Not 149–161 (overlaps the drone's 5.8 GHz band). **2.4 GHz** kept on, for the robot only |
| DJI app | Package name **`com.rufino.djirelay`**, App Key from developer.dji.com (see README) |

**Radio plan.** The DJI O3 link can use 2.4 GHz or 5.8 GHz. On 2.4 GHz it would
share the band with the robot's ESP8266. The app sets
`AirLinkKey.KeyFrequencyBand` to `BAND_5_DOT_8G`, but only if
`KeyFrequencyBandRange` offers it (**[VERIFY]**; depends on region and firmware),
and logs the result. That gives three separate bands: O3 on 5.8 GHz, the phone on
5.2 GHz, the robot on 2.4 GHz.

---

## 7. Android app

### 7.1 Base project (verified against SampleCode-V5)

- MSDK **5.20.1**: `implementation com.dji:dji-sdk-v5-aircraft`, `compileOnly com.dji:dji-sdk-v5-aircraft-provided`, `runtimeOnly com.dji:dji-sdk-v5-networkImp`. `networkImp` is already a dependency in the `dji-sdk-v5-aircraft` POM, and the main sample module does not declare it; it is declared explicitly anyway.
- **Runtime libraries missing from the MSDK POM** (found in F1): the SDK needs `com.iqiyi.xcrash:xcrash-android-lib:3.1.0`, `io.reactivex.rxjava3:rxkotlin:3.0.0` and `androidx.documentfile:documentfile:1.0.1`, which the sample lists as "required by the SDK" in `open-pilot-plugin-sample/build.gradle`. Without xcrash, `SDKManager.init` crashes with `NoClassDefFoundError: xcrash/ICrashCallback`.
- **Implement every method of MSDK listener interfaces, including those marked `default`** in the provided jar. The runtime classes loaded by `Helper.install` declare some of them abstract: `AvailableCameraUpdatedListener.onCameraStreamEnableUpdate` throws `AbstractMethodError` if it is not overridden (found in F1).
- AGP 8.7.0, Gradle 8.12, Kotlin 2.1.0, NDK 28.0.13004108, compileSdk/targetSdk 35, minSdk 24, `abiFilters 'arm64-v8a'`. Gradle build files in **Groovy**, with the sample's layout (`dependencies.gradle` with `ext.deps`, SDK versions in `gradle.properties`).
- **Build JDK:** Gradle 8.12 runs on Java 17–23. Android Studio's bundled JBR is Java 25 (`Unsupported class file major version 69`), and a JRE has no compiler (`does not provide the required capabilities: [JAVA_COMPILER]`). Use a full JDK 21 (`openjdk-21-jdk`), set as `org.gradle.java.home` in `~/.gradle/gradle.properties` and as the Gradle JDK in Android Studio (see `docs/dev-environment.md`).
- The `Application` is a plain `android.app.Application` subclass (`RelayApplication`). In the sample, `DJIApplication` is a class of the sample itself, not of the SDK. It calls `com.cySdkyc.clx.Helper.install(this)` in `attachBaseContext` (before any MSDK class is used) and initialises the SDK in `onCreate`.
- `packagingOptions`: copy the `doNotStrip` list and `pickFirst 'lib/arm64-v8a/libc++_shared.so'` from the sample. Set `android:extractNativeLibs="true"`, and also `jniLibs { useLegacyPackaging true }` (the AGP 8 form used by the 5.20.1 open-pilot-plugin-sample).
- Manifest: meta-data `com.dji.sdk.API_KEY` = `${API_KEY}` (from `manifestPlaceholders`). The key comes from `DJI_API_KEY` in `~/.gradle/gradle.properties` and is never committed; the build fails with an explicit message if it is missing. Add `uses-feature android.hardware.usb.accessory`, and a USB-attach activity with `USB_ACCESSORY_ATTACHED` and `@xml/accessory_filter`. That XML file is **provided by the MSDK AAR** (DJI accessory models `T600`, `HG210`, `WM160`, `com.dji.logiclink`, `com.dji.link`); the app does not define its own.
- **No UXSDK.** UI in **Jetpack Compose**. The video goes in a `SurfaceView` hosted with `AndroidView`.
- Packages: `sdk/`, `stream/`, `rtp/`, `net/`, `telemetry/`, `camera/`, `storage/`, `ui/`, `service/`.

### 7.2 Initialisation, registration, connection (verified)

1. `SDKManager.getInstance().init(context, InitSettings(SDKLinKMode.LOCAL), SDKManagerCallback)`.
2. In `onInitProcess(event, total)`, when `event == DJISDKInitEvent.INITIALIZE_COMPLETE`, call `SDKManager.getInstance().registerApp()`.
3. `SDKManagerCallback` has nine abstract methods, all of which must be implemented: `onRegisterSuccess`, `onRegisterFailure(IDJIError)`, `onProductConnect(productId)`, `onProductDisconnect(productId)`, `onProductChanged(productId)`, `onInitProcess(DJISDKInitEvent, int)`, `onDatabaseDownloadProgress(long, long)` (deprecated), `onRemoteServerStateUpdated(ServerState)` and `onRemoteServerCompatibleError(IDJIError)`. The last three belong to DJI Open Pilot and are only logged.
4. As in the sample, call `registerApp()` again when `DJINetworkManager.addNetworkStatusListener` (`isNetworkAvailable(boolean)`) reports that the network is available and the app is not yet registered.
5. Product connection: `FlightControllerKey.KeyConnection` (Boolean). It is a `DJIKeyInfo<Boolean>`: create the key with `KeyTools.createKey(...)` and subscribe with `KeyManager.getInstance().listen(key, holder, getOnce = true, listener)`. Also subscribed from F1 and logged: `ProductKey.KeyProductType`, `ProductKey.KeyFirmwareVersion` (aircraft firmware), `RemoteControllerKey.KeyConnection`, `RemoteControllerKey.KeyFirmwareVersion` and `RemoteControllerKey.KeyFirmwareMatchType` (the aircraft family the controller firmware is built for, e.g. `DJI_MINI_3_PRO`; it returned `UNKNOWN` with the Mini 3 Pro and RC-N1 in F1).
6. Only after connection: subscribe to telemetry, add the stream listeners, and enable the configuration UI.
7. On disconnection: remove listeners, cancel key subscriptions, and emit `reconnect` when the drone comes back.

### 7.3 Receiving the stream (verified)

- Manager: `MediaDataCenter.getInstance().cameraStreamManager`.
- Camera index: from `addAvailableCameraUpdatedListener` (`onAvailableCameraUpdated(list)`). It is `ComponentIndexType.LEFT_OR_MAIN` on the Mini 3 Pro (V11, F1). Never hard-code it.
- `addReceiveStreamListener(index, listener)`, where the listener is `onReceiveStream(data: ByteArray, offset: Int, length: Int, info: StreamInfo)`. Remove it with `removeReceiveStreamListener(listener)`.
- `StreamInfo`: `getMimeType()` (`MimeType.H264` / `H265`), `getWidth()`, `getHeight()`, `getFrameRate()`, `getPresentationTimeMs()`, `isKeyFrame()`, and **`getSeiList()`** (`List<SEIData>` with `seiType` and `mSeiData`; not in the public docs).
- Diagnostics only: `getAircraftStreamFrameInfo(index)` → `FrameInfo` (`nalUnitList`, `isAbsoluteFrame`, `mayIllegalFrame`, `frameContentOffset`); `getVideoStreamFormat`, `getMediaFormat`, `setStreamPriority`, `enableStream`.
- There is **no API to choose the live-stream codec**. The app relays what arrives.
- Pilot view: `putCameraStreamSurface(index, surface, w, h, ScaleType)` and `removeCameraStreamSurface(surface)`. It can be switched off. `setKeepAliveDecoding(true)` only if the first frame is slow to appear.
- `setStreamEncoderBitrate(index, bps)`: an advanced setting, unchanged by default. A higher live bitrate may help SSL-Vision (section 11.1).

**Callback rules:**
1. Stamp `SystemClock.elapsedRealtimeNanos()`.
2. Copy `data[offset, offset+length)` into a buffer from a pre-allocated pool.
3. Enqueue it in the bounded queue (about 1 s) of each active consumer: the live sender (F3) and, while it runs, the phone recording (7.10). Each consumer has its own queue, so a slow one never delays the other.
4. Return.

**Reconnection (F3):** after a drone reconnection, the re-added listener may receive no callbacks although the pilot view shows video. A watchdog removes and adds the listener again while it is attached and has had no callback for 5 s (at most every 5 s); in F3 the stream resumed after two re-adds, about 10 s after the drone reconnected.

No I/O and no large allocations in the callback. **Always copy**: the sample hands `data` to another thread without copying. **Never drop the first callback**: the sample does, and it may carry SPS/PPS. When a consumer's queue is full, that consumer drops frames until the next keyframe and emits a `drop` event.

### 7.4 NAL parsing

- E1 (F2): the live stream is **Annex B** (4-byte start codes), and **one callback is one access unit**. Each callback is `[non-IDR]` or `[SPS PPS IDR]`; there are no AUD and no SEI NAL units. The parser still checks for a start code and logs an `error` event if a callback does not begin with one.
- H.264 NAL types: 7 SPS, 8 PPS, 5 IDR, 1 non-IDR, 6 SEI, 9 AUD (AUD is dropped when sending).
- SPS and PPS come before every IDR (V4, E1). The parser still caches the latest SPS/PPS and inserts them before any IDR that arrives without them.
- The SEI comes only as side data in `StreamInfo.getSeiList()`: one entry of type 240, 25 bytes, per frame (V14, E1). Its format is undocumented; it is sent raw in the `frame` message.
- If `getMimeType()` reports H265: log a `mime_changed` event and use the RFC 7798 packetizer. It is only implemented if E1 shows that it is needed.

### 7.5 RTP packetizer (RFC 6184)

- 12-byte header: V=2, PT=96, 16-bit sequence, 90 kHz timestamp, random SSRC fixed for the session, marker bit on the last packet of each access unit.
- Single NAL unit packets, and FU-A (type 28) for larger NAL units. No STAP-A. Maximum payload 1200 bytes (configurable up to about 1400).
- **Continuous RTP timestamp:** within a segment, **`pts_ms` × 90** (decided from E2: `presentationTimeMs` is UTC epoch milliseconds and monotonic; it tracks the arrival times within a few ms but is not a fixed-rate encoder clock). After a reconnection, the segment counter increments, and the timestamp is re-anchored so that it stays monotonic. SSRC and sequence continue.
- **Header extension (RFC 8285, one-byte form, `0xBEDE`)** on the first packet of each access unit:

  | ID | Length | Content |
  |---|---|---|
  | 1 | 8 bytes | `t_rx_ns`: phone receive time, `elapsedRealtimeNanos`, big-endian int64 |
  | 2 | 8 bytes | `pts_ms`: raw `presentationTimeMs`, big-endian int64 |
  | 3 | 6 bytes | `frame_idx` (uint32) + `segment` (uint16), big-endian |

  32 bytes in total, including padding. `rtph264depay` ignores it. The wire format is fixed in `docs/protocol.md`.

### 7.6 Transport

- Unicast to the PC's IP, which is entered in the app and persisted.
- Request the Wi-Fi network (`TRANSPORT_WIFI`) and **bind each socket** with `Network.bindSocket`. Never use `bindProcessToNetwork`.
- Video socket: `setTrafficClass(0x88)` (DSCP AF41). `WifiLock` `WIFI_MODE_FULL_LOW_LATENCY`, held while live streaming (from F3).
- (F6) Foreground service of type **`connectedDevice`** (permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`), a `PARTIAL_WAKE_LOCK`, and `FLAG_KEEP_SCREEN_ON` for the whole session.
- High-priority sender thread. No allocation per packet.

### 7.7 Messages on UDP :5601

JSON, one message per datagram. Common fields: `type`, `seq` (per type),
`t_ph_ns` (`elapsedRealtimeNanos` at send time), `t_ph_utc_ms` (for humans only),
`phone_session` (the phone's session start, UTC).

- **`frame`** (one per access unit): `frame_idx`, `segment`, `rtp_ts`, `rtp_seq_first`, `rtp_seq_last`, `pts_ms`, `t_rx_ns`, `t_tx_ns`, `key`, `size`, `nal_types`, `sei` (count and types). On keyframes and on change, also `w`, `h`, `fps`, `mime`.
- **`telemetry`** (one per key update): `{key, value, t_ph_ns}`. The keys (verified) are:

  | Group | Keys |
  |---|---|
  | Flight controller | `KeyAircraftAttitude`, `KeyAircraftVelocity`, `KeyAircraftLocation3D`, `KeyUltrasonicHeight` (dm), `KeyTakeoffLocationAltitude`, `KeyGPSSatelliteCount`, `KeyGPSSignalLevel`, `KeyFlightMode`, `KeyAreMotorsOn`, `KeyIsFlying`, `KeyWindSpeed`, `KeyWindDirection`, `KeyWindWarning`, `KeyIsLowBatteryWarning`, `KeyIsSeriousLowBatteryWarning` |
  | Gimbal | `KeyGimbalAttitude`, `KeyYawRelativeToAircraftHeading` |
  | Camera | `KeyIsRecording`, `KeyRecordingTime`, `KeyCameraStorageInfos`, `KeyNewlyGeneratedMediaFile` |
  | Battery | `KeyChargeRemainingInPercent`, `KeyVoltage` |
  | Air link | `KeyFrequencyBand`, `KeyUpLinkQuality`, `KeyDownLinkQuality` |

  Subscriptions use `KeyManager.getInstance().listen(key, holder, listener)` and are cancelled with `cancelListen(holder)`. A `key_rates` message every 10 s reports the measured update rate of each key.
- **`event`**: `session_start` (codec, resolution, RTP timestamp mode, SSRC, app and MSDK versions, payload size), `session_stop`, `record_start_requested`, `record_started` (when `KeyIsRecording` turns true), `record_stopped`, `media_file`, `camera_config_applied`, `camera_config_snapshot`, `airlink_band`, `gimbal_nadir`, `drop`, `reconnect`, `mime_changed`, `error`.
- **`clock`**: see 7.8.

### 7.8 One-way clock beacons

- A dedicated thread sends `{type:"clock", seq, t_ph_ns}` at 10 Hz. `t_ph_ns` is taken immediately before `sendto`.
- `drone_logger` stores each beacon with `rx_wall`, `rx_monotonic` and the kernel receive time. For each beacon, `d = t_pc − t_ph` is the offset plus the one-way delay. The lower envelope of `d` (minimum per 10 s window), fitted with a straight line, gives offset + drift.
- The fit is done offline in the analysis. `drone_logger` also computes it live, for its health line.
- The drift comes out exact. The absolute offset carries a constant bias of about 1–3 ms, which is accepted: Stage 1's LED fit is the final timing reference.

### 7.9 Drone configuration (verified keys; app UI only)

Applied as a sequential state machine. Each step has a completion callback and a
timeout, and failures are logged and shown.

| Step | Key | Notes |
|---|---|---|
| 1 | `AirLinkKey.KeyFrequencyBand` = `BAND_5_DOT_8G` | Only if `KeyFrequencyBandRange` contains it; `airlink_band` event |
| 2 | `KeyCameraMode` = `VIDEO_NORMAL` | Required before changing resolution and frame rate |
| 3 | `KeyVideoCodecFormat` = `H265` | Expected to set the recording codec **[VERIFY]** |
| 4 | `KeyVideoResolutionFrameRate` from `KeyVideoResolutionFrameRateRange` | `RESOLUTION_3840x2160` at 60 fps. Log which of `RATE_59FPS`, `RATE_60FPS` or `RATE_PRECISE_60FPS` was accepted |
| 5 | `KeyVideoFileFormat` | MP4 |
| 6 | `KeyExposureMode` = `MANUAL` | Required for ISO and shutter. Also keeps SSL-Vision's colour calibration valid |
| 7 | `KeyISO`, `KeyShutterSpeed` | From the app preset; validate against the range keys |
| 8 | `KeyWhiteBalance` | Fixed, for example 5500 K. Same reason as step 6 |
| 9 | `KeyCameraColor` from `KeyCameraColorRange` | Read the range after steps 3–4 |
| 10 | `KeyCameraFocusMode`, `KeyAntiFlicker` | Mini 3 Pro: `MANUAL`/`AF` only; antiflicker off outdoors |
| 11 | `KeyCameraVideoCaptionEnabled` = true | Only if `KeySubtitlesSettable`. **Log whether it was accepted** |
| 12 | Read back everything → `camera_config_applied` | Repeated every 30 s and on reconnection (`camera_config_snapshot`) |

- Recording: `KeyStartRecord` / `KeyStopRecord` (`performAction`), confirmed by `KeyIsRecording`.
- Gimbal: `GimbalKey.KeyRotateByAngle` with `GimbalAngleRotation(mode = ABSOLUTE_ANGLE, pitch = −90.0, rollIgnored = true, yawIgnored = true, duration ≈ 1 s)`.
- The preset is edited and persisted in the app.

### 7.10 Phone recording

The live stream to the PC is the primary path; the PC records the session. The
phone recording is a separate copy, used for the stream-format tests (E1, E2) and
for debugging.

- Started and stopped only by the user ("Phone recording" control). Independent of
  the "Live streaming" toggle and of the drone's microSD recording.
- Each recording goes in its own folder, named with the UTC date and time, in the
  app-specific storage (`Android/data/com.rufino.djirelay/files/sessions/`), and is
  copied to the PC with `adb pull`:
  - `stream.h264`: the callback bytes exactly as received (the format is confirmed
    in E1);
  - `messages.jsonl`: one `frame` message per callback, with `file_offset` into
    `stream.h264` and the SEI payloads (base64), plus the events.
- Its own bounded queue and a dedicated writer thread. The live path never waits
  for it: if the recording queue is full, only the recording drops frames and
  writes a `drop` event.
- Recording does not start when the free space is too low.

### 7.11 UI (Compose)

- **Status:** SDK registered, drone connected, O3 band, codec/resolution/fps, send rate, frames/s, drops, recording, microSD free space, battery, GPS, height, subtitles accepted.
- **Controls:** "Live streaming" on/off (relay to the PC), "Phone recording" start/stop (7.10), "Drone recording (microSD)" start/stop, apply and edit the preset, gimbal nadir, PC IP, pilot view on/off.
- **Alerts:** low battery, link loss, PC unreachable.
- The screen is kept on for the whole session. The app never sends flight commands.

---

## 8. `drone_logger` (PC, C++23)

### 8.1 Repository layout (D6, decided)

`drone_logger` lives **inside `ssl-aggregator`**, and `ssl-aggregator` is a **git
submodule** of this repository:

```
<this repository>/
  android/            Android app
  docs/               specification, protocol, experiments
  ssl-aggregator/     git submodule → github.com/LimaBR/ssl-aggregator (branch main)
    drone_logger/     new
    libcommon/ robot_logger/ vision_logger/ sql/ scripts/ grafana/ analysis/   existing
```

Changes to `ssl-aggregator` (made in the phases listed, not now):

| File | Change | Phase |
|---|---|---|
| `CMakeLists.txt` | `add_subdirectory(drone_logger)`, with an option to skip it when GStreamer is absent | F3 |
| `drone_logger/` | New logger: RTP receive and frame assembly, GStreamer pipeline (recording, injection, display), message parsing, configuration check | F3–F4 |
| `libcommon` | Add a **unicast** UDP receiver (the existing `UdpReceiver` only joins multicast groups) with `SO_TIMESTAMPNS` and `recvmmsg` | F3 |
| `sql/00_init.sql` | Add schema `drone` to the schema creation and the grants | F4 |
| `sql/04_drone.sql` | Tables of section 8.4 | F4 |
| `sql/05_vision_source.sql` | Add `t_capture_camera`, `capture_session` and `source` to `vision.robot_detection` and `vision.ball_detection` (section 9.3) | F4 |
| `vision_logger` | Store those three columns; `source` from the new `.env` key `VISION_SOURCE` (default `live`) | F4 |
| `scripts/db_init.sh` | Also run `04` and `05` | F4 |
| `scripts/export.sh` | Also export the `drone.*` tables | F4 |
| `grafana/capture_health.json` | Drone panels: live fps, frame loss, Mb/s, injection rate, recording state, configuration mismatches, robot loss while flying | F4 |
| `.env.example` | `DRONE_*` and `VISION_SOURCE` keys (see this repository's `.env.example`) | F3–F4 |
| `analysis/` | Phone-clock fit; live-frame time correction; loaders that filter vision rows by `source` (section 10). 4K replay time map only if F7 is done | F4, F7 |

### 8.2 Conventions inherited from `ssl-aggregator`

- C++23, `-Wall -Wextra -Wpedantic`, `std::print`, `std::jthread`.
- `common::load_dotenv()` first, then `env_str` / `env_int` with defaults.
- `common::now_rx()` right after each receive → `rx_wall` + `rx_monotonic` in every row. Video packets and clock beacons also store the kernel receive time (`SO_TIMESTAMPNS`, `CLOCK_REALTIME`, ns).
- `capture_session` = the logger's start time, stamped on every row.
- `BlockingQueue` + `BatchWriter` (one per table); no `ON CONFLICT` and no silent drops.
- A health line to stderr once per second: frames/s, Mb/s, lost packets and frames, injected frames/s, latest offset estimate, recording state, mismatch count.
- Clean exit via the shutdown handler, then `std::quick_exit` (the libpqxx 7.10 issue).

### 8.3 Video path

- Own UDP socket on `DRONE_VIDEO_PORT` (5600). Every RTP packet is used for two things:
  1. **Frame assembly:** packets are grouped by RTP timestamp up to the marker bit. Extension fields, sequence gaps, first and last arrival times are recorded, giving one `drone.live_frame` row per access unit.
  2. It is pushed into `appsrc` (caps `application/x-rtp, media=video, clock-rate=90000, encoding-name=H264, payload=96`) → `rtpjitterbuffer` (latency `DRONE_JITTER_LATENCY_MS`, `drop-on-latency=true`) → `rtph264depay` → `h264parse config-interval=-1` → `tee`:
     - **recording:** `matroskamux` → `video_live.mkv` (original bitstream);
     - **decoding:** `DRONE_DECODER` (`nvh264dec`, fallback `avdec_h264`) → `tee`:
       - **SSL-Vision injection** (on by default, `DRONE_INJECT=1`): `videoconvert` → `video/x-raw, format=YUY2` at the live stream's resolution, `framerate=30/1` → `v4l2sink device=$DRONE_V4L2_DEVICE sync=false`. A pad probe before the sink stamps each frame's injection time (`now_rx()`) and its RTP timestamp into `drone.live_frame`;
       - **display** (optional, `DRONE_SHOW_VIDEO`): `videoconvert` → sink with `sync=false`.
- As built in F3, this is split into two GStreamer pipelines:
  - a **stream pipeline**, built for each stream: on the first packet, on a new SSRC, and after no packets for `DRONE_STREAM_TIMEOUT_MS` (2000). It runs from `appsrc` to the recording (`video_live_NN.mkv`, NN = stream number in the session, finalised with EOS when the stream ends) and to the decoder, whose YUY2 frames go to an `appsink`;
  - an **injection pipeline**, built on the first decoded frame and kept for the whole run: `appsrc` → `v4l2sink` (+ the optional display). The v4l2loopback device therefore stays open across stream interruptions; while no frames arrive, nothing is written, and SSL-Vision simply waits. It is rebuilt only if the resolution changes, or after an error (at most every 2 s).
- Caps for `v4l2sink` (F3): v4l2loopback 0.12 accepts YUY2 only with `interlace-mode=progressive` and `colorimetry=2:4:7:1`, and refuses the decoder's extra fields; the drone's SPS has no frame rate (`0/1`). The injection caps are therefore set to format, size, pixel aspect ratio, those two fields and 30/1. They are labels only: no pixel conversion, and frames are passed on as they arrive (`sync=false`).
- Until F4, the per-frame rows go to CSV files in the session folder (8.6) instead of `drone.live_frame`.
- Optional per-packet log (`DRONE_PACKET_LOG=1`): `rtp_packets.csv` next to the `.mkv`, for experiments E1–E5. It is off by default.

### 8.4 Database: schema `drone`

All tables have `id BIGSERIAL`, `rx_wall TIMESTAMPTZ NOT NULL`, `rx_monotonic DOUBLE PRECISION` and `capture_session TIMESTAMPTZ`.

| Table | One row per | Main columns |
|---|---|---|
| `drone.live_frame` | access unit assembled from RTP | `segment`, `frame_idx`, `rtp_ts`, `seq_first`, `seq_last`, `n_packets`, `n_lost`, `complete`, `key`, `size`, `t_rx_ns` and `pts_ms` (from the extension), `first_rx_*`, `last_rx_*`, `last_rx_kernel_ns`, **`inject_wall`, `inject_monotonic`** (NULL if the frame was not decoded or injected) |
| `drone.phone_frame` | `frame` message | `segment`, `frame_idx`, `t_rx_ns`, `t_tx_ns`, `pts_ms`, `key`, `size`, `nal_types`, `sei_count`, `w`, `h`, `fps`, `mime` |
| `drone.telemetry` | `telemetry` message | `key`, `value JSONB`, `t_ph_ns`, `seq` |
| `drone.event` | `event` and `key_rates` messages | `type`, `payload JSONB`, `t_ph_ns`, `seq`, `phone_session` |
| `drone.clock_beacon` | `clock` message | `seq`, `t_ph_ns`, `rx_kernel_ns` |
| `drone.config_check` | check result | `field`, `expected`, `reported`, `ok` |

Indexes: `(capture_session, segment, frame_idx)` on the frame tables, and `rx_wall`
on every table. `live_frame` and `phone_frame` are joined on
`(capture_session, segment, frame_idx)`.

### 8.5 Configuration check

The live `w/h/fps/mime` and `camera_config_applied` / `camera_config_snapshot` are
compared with the `DRONE_EXPECT_*` keys. Mismatches are printed prominently and
stored in `drone.config_check`. Accepting 59.94 or 60 fps is a profile option.

### 8.6 Files per session

```
$DRONE_OUTPUT_DIR/<capture_session>/
  video_live_NN.mkv   # original live bitstream (H.264), one per stream (8.3)
  live_frames.csv     # one row per access unit (until F4, then drone.live_frame)
  injected_frames.csv # injection time and RTP timestamp per frame (until F4)
  rtp_packets.csv     # only with DRONE_PACKET_LOG=1
  session.json        # effective configuration, versions, SSRC (F4)
  DJI_xxxx.MP4        # 4K/60 H.265, copied from the microSD afterwards
  DJI_xxxx.SRT        # subtitles, if they were accepted
```

---

## 9. SSL-Vision integration (D7, decided)

SSL-Vision processes the drone video and **always runs live**, indoors and
outdoors. The drone is one SSL-Vision camera. If other cameras are present (for
example the ceiling camera indoors), SSL-Vision runs with several camera threads
(`-c <n>`), and the drone is told apart by its `camera_id`.

### 9.1 Live injection (v4l2loopback → V4L module)

- The `v4l2loopback` kernel module creates a virtual camera, for example `/dev/video10` (`video_nr=10 card_label="DJI drone" exclusive_caps=1 max_buffers=4`; SSL-Vision's V4L module requests 3 buffers and fails with the default of 2, see V19 in F3). `drone_logger` writes decoded frames to it; SSL-Vision reads it like a USB webcam.
- SSL-Vision settings for that camera thread: capture module **V4L**, `cam idx` = the device number, `width` and `height` = the live stream's resolution (SSL-Vision has no requirement on it), `framerate` 30, `pixel format` **YUYV**.
- Advantages: SSL-Vision needs **no code changes**; the device survives stream interruptions; `drone_logger` knows exactly when each frame was handed over (`inject_*`), which makes the correction in section 10.2 possible.
- **[VERIFY] V18:**
  - Does the V4L2 buffer timestamp written by `v4l2sink` reach SSL-Vision through v4l2loopback (as `t_capture_camera`)?
  - If it does, `drone_logger` could set it to the frame's estimated capture time, and SSL-Vision would publish it directly.
  - If not, the correction in section 10.2 is done in the analysis.
- The V4L module opens `/dev/video<cam idx>` (verified in the source). **[VERIFY] V19:** it accepts YUYV at the live resolution from v4l2loopback.
- Alternative, not chosen: forward the RTP packets to a local port and let SSL-Vision's **Video** module decode them through an OpenCV GStreamer pipeline. It also needs no SSL-Vision change, but `t_capture_camera` would be a synthetic `k/30` value, decoding moves into SSL-Vision, and an interruption ends the capture until it is restarted by hand.

### 9.2 4K replay after the flight (conditional, F7)

Implemented only if E9 shows that SSL-Vision's live detection is not good enough
(section 11.1). The `source` and `t_capture_camera` columns are still added in F4,
because they cost little and keep this path open.

- SSL-Vision's **Video** module opens the MP4 (`cv::VideoCapture`; OpenCV's FFmpeg backend decodes H.265). It sets `t_capture_camera = k / fps` from the start of the file, using the container's frame rate.
- SSL-Vision processes the file as fast as it can. `t_capture` is then the replay time and is meaningless, but `t_capture_camera` gives the exact frame time within the file.
- During a replay, `vision_logger` runs with `VISION_SOURCE=replay:<file name>`, so the rows never mix with live data.
- The replay needs its own SSL-Vision calibration, because pixel coordinates at 4K differ from those of the live stream.

### 9.3 Changes to `vision_logger` and the vision tables

- New columns in both vision tables: `t_capture_camera DOUBLE PRECISION` (raw, in the unit the capture module uses: ns for V4L, s for Video), `capture_session TIMESTAMPTZ` and `source TEXT`.
- Existing rows keep `source = NULL`, which the analysis reads as "live, before this change". Existing queries keep working.

---

## 10. Time chain

The goal is a capture time for every detection, on the PC's epoch clock (the same
family as `rx_wall`), so that Stage 1 can match the LED edges against
`robot.telemetry.led_state`, as it already does.

### 10.1 What each path gives

| Path | `t_capture` from SSL-Vision | Problem |
|---|---|---|
| Live (9.1) | PC wall time when SSL-Vision picks up the injected frame | Includes the link latency (~120–250 ms) **and its jitter**. Stage 1 absorbs the mean, but the jitter becomes noise in the camera time, and so in velocity |
| Replay (9.2) | Replay time (meaningless) | Use `t_capture_camera` + the file's start time instead |

### 10.2 Live: per-frame correction (analysis)

1. **Phone clock → PC clock:** offset + drift from `drone.clock_beacon` (bias of about 1–3 ms).
2. **Estimated capture time of each live frame:** chosen in the F4 analysis. E2 (F2) showed that `pts_ms` is UTC epoch milliseconds that moves with the arrival times (correlation 0.91, sd of `t_rx − pts` about 4 ms), not a fixed-rate encoder clock, and that neither `pts_ms` nor the arrival times fit a regular 30 fps grid. The two options considered before E2 (map `pts_ms` through the lower envelope of `(arrival − pts)`, or fit a regular 30 fps grid) therefore do not apply as they are.

   The constant part of the latency remains, and Stage 1 absorbs it.
3. **(Decided in F4)** **Match** each SSL-Vision frame (`vision.*`, drone `camera_id`) to a `drone.live_frame` by `t_capture` ≈ `inject_wall` (nearest, within a few ms). Then replace `t_capture` with the estimated capture time. Stage 1 already absorbs the constant latency and the drift; this step only removes per-frame jitter. Since `pts_ms` itself follows the arrival times (E2), it would remove only the PC-side part of that jitter. F4 decides, from E10 and the analysis data, whether the step is needed; if not, it is dropped.
4. **Stage 1** as today: the robust linear fit to the LED edges absorbs the remaining offset and any rate error.

### 10.3 Replay: file time map (analysis)

1. **File start on the PC clock:**
   - from `record_started` / `media_file` (mapped through step 1 of 10.2);
   - cross-checked with the LED edges seen in the live video;
   - and with the `.SRT` date and time, if subtitles were accepted.

   The latency from `record_started` to the first recorded frame is estimated in E3 (V17).
2. **Frame k:** `t_start + t_capture_camera_k`. Use the MP4 timestamps and the container frame rate, never a nominal 60, since the real rate may be 59.94.
3. **Stage 1** refines the result with the LED edges.

Requirement for both paths before Stage 1: an error below ±0.5 s, Stage 1's
matching window. The target is < 0.2 s.

---

## 11. Ground-truth risks (for the analysis; they affect how captures are set up)

### 11.1 Image resolution

At 11 m, with a horizontal field of view of about 74° (16:9 video; **[VERIFY]**
the real video FOV), one pixel covers about **8.6 mm in a 1920-pixel-wide live** stream
and about **4.3 mm in 4K**. The live stream measured in F2 is 1440×1080 (4:3); these
figures are recomputed once F5 fixes the camera mode and E9 measures the real field of view. The dots of the standard SSL pattern are about 40–50 mm
across. That is about 5 px live, and less in colour, because H.264 halves the
colour resolution (4:2:0), plus compression artefacts. In 4K it is about 10 px.

Live detection of the robot and of the LED may therefore be unreliable at that
height. E9 measures it. Possible mitigations:
- fly lower;
- use larger markers, if SSL-Vision's pattern settings allow other sizes **[VERIFY]**;
- raise the live bitrate (`setStreamEncoderBitrate`);
- if none of these is enough, use the 4K replay (9.2, phase F7) for the labels, and keep the live path for real time and monitoring.

### 11.2 The camera moves

SSL-Vision assumes a fixed, calibrated camera. A hovering drone drifts and yaws
with the wind, so the poses come out in a moving frame, and the drone's own
motion would contaminate the robot's ground speed. Proposed mitigation: place at
least two **static SSL patterns** (robot IDs not in use) on the ground, inside the
image. The analysis then expresses the robot's pose relative to them, frame by
frame. The drone's attitude and velocity telemetry is kept for diagnostics.

### 11.3 Outdoor colour calibration

Sunlight and shadows change the colours. Manual exposure and fixed white balance
(section 7.9) keep the image stable during a flight. SSL-Vision's colour
thresholds must be calibrated on site, and probably again if the light changes.

---

## 12. Latency budget and measurement

| Stage | Estimate |
|---|---|
| Camera → controller → phone (DJI link) | ~120 ms (DJI figure) |
| Callback → last packet sent | < 5 ms (NFR1) |
| Wi-Fi uplink + Ethernet | a few ms |
| Jitter buffer + depacketizer | 20–50 ms |
| Decode + conversion + v4l2loopback | < 20 ms (NFR1) |
| **Total until the frame is in SSL-Vision** | **~170–270 ms** |

**Glass-to-glass test:** point the drone camera (propellers removed) at a screen
showing a millisecond timer, put the `drone_logger` display next to it, and
photograph both with a third camera several times. The robot's LED gives a second
check: the edges in `robot.telemetry` against the same edges in SSL-Vision's
drone detections.

---

## 13. Open items

| # | Question | How |
|---|---|---|
| V2 | Annex B or AVCC? One callback = one access unit? Answered: Annex B; yes | E1 (F2) |
| V3 | Reference of `presentationTimeMs`. Answered: UTC epoch ms, monotonic, tracks arrival; not a fixed-rate encoder clock. RTP timestamp = `pts_ms` × 90 | E2 (F2) |
| V4 | SPS/PPS before every IDR? Answered: yes | E1 (F2) |
| V5 | Live stream keeps its resolution and 30 fps while recording 4K/60 H.265? (1440×1080 measured in F2, camera mode not set; checked again in video mode) | F5, E3 |
| V7 | Real update rate of the attitude and gimbal keys | `key_rates` |
| V10 | Codec actually delivered on the live stream. Answered: H.264 High, level 4.0 | E1 (F2) |
| V11 | Camera index value for the Mini 3 Pro. Answered: `LEFT_OR_MAIN` | F1 log |
| V12 | `KeyVideoCodecFormat` sets the recording codec | F5, read back + file check |
| V13 | Subtitles accepted by the Mini 3 Pro, and their time resolution | F5 |
| V14 | Content of the SEI entries (any capture timestamp?). Partly answered: side data only, type 240, 25 bytes per frame; format undocumented, no field identified as a capture timestamp | E1 (F2) |
| V16 | `KeyFrequencyBand` can be set to 5.8 GHz on the Mini 3 Pro in Brazil | F5 |
| V17 | Latency from `record_started` to the first frame of the 4K file | E3 |
| V18 | V4L2 buffer timestamp passes through v4l2loopback to SSL-Vision's `t_capture_camera` | E10 (F4) |
| V19 | SSL-Vision's V4L module accepts YUYV at the live resolution from v4l2loopback (it opens `/dev/video<cam idx>`, verified in the source). Answered: yes, at 1440×1080, with v4l2loopback `max_buffers=4` | F3 bench |
| V20 | SSL-Vision builds on Ubuntu 24.04 (its install script supports 20.04–24.04; Qt5 packages must exist). Built in `~/PUC/ssl-vision` (`bin/vision` exists); it runs on the same PC as the loggers. Confirmed | Environment setup, before F3 |
| V21 | Real video field of view of the Mini 3 Pro at 4K and at the live resolution | E9 (field calibration) |

---

## 14. Tests and acceptance criteria

- **E1 Stream format (bench):** record 60 s of callbacks (bytes + metadata + SEI). *Accept:* decodes with FFmpeg/GStreamer, and the format, NAL layout, SPS/PPS behaviour and codec are documented.
- **E2 PTS reference:** compare `pts_ms` with `t_rx_ns`. *Accept:* the RTP timestamp mode is chosen, and the live-time correction method (10.2 step 2) is chosen or assigned to a later phase (F2: RTP timestamp = `pts_ms` × 90; correction in F4).
- **E3 Simultaneous recording:** 10 min of 4K/60 H.265 while relaying. *Accept:* `StreamInfo` stable, live loss < 1 %, 4K file intact, configuration check passes, `record_started` → first-frame latency measured with the LED (V17).
- **E4 Latency:** glass-to-glass. *Accept:* < 300 ms (p95) to the display.
- **E5 Network:** 30 min with `robot_logger` and SSL-Vision running. *Accept:* no latency growth; robot loss at baseline (NFR6).
- **E6 Robustness:** kill `drone_logger`, stop PostgreSQL, power off the AP, power-cycle the drone. *Accept:* the app resumes on its own, segments are marked, no database rows are lost, and SSL-Vision resumes detecting without being restarted.
- **E7 Clock:** residual of the beacon fit (lower envelope) < 2 ms over 30 min.
- **E8 Field:** flight at the chosen height over the track, with the robot and the static reference patterns. *Accept:* every table and file in sections 8.4 and 8.6 complete; robot loss at baseline; the time maps of section 10 within ±0.5 s; Stage 1 converges on the drone's LED edges (live; and replayed too, if F7 was done).
- **E9 SSL-Vision on the drone:** at two or three heights, live through the virtual camera. Compare with the same flight's 4K file opened by hand in SSL-Vision's Video module, which needs no new code. *Accept:* documented detection rates of the robot and of the LED-as-ball, and the position noise of a static pattern. The height and the marker size are chosen from these results. **Decision point:** if live detection is not good enough at any practical height and marker size, phase F7 is done.
- **E10 Injection timing (F4):** distribution of `t_capture − inject_wall` for the drone camera, and V18. *Accept:* the frame matching of 10.2 step 3 is unambiguous (spread well below 33 ms), or F4 decides that step 3 is not needed. `drone_logger` writes the injection times from F3 on (`injected_frames.csv`).

---

## 15. Phases

Each phase ends with its tests and waits for the user's validation before the next one starts.

Prerequisites outside the code:

| Needed before | What |
|---|---|
| F1 | DJI App Key registered for `com.rufino.djirelay` (README) |
| F3 | Push access to `LimaBR/ssl-aggregator` (the user is arranging it); SSL-Vision installed on the PC (V20); v4l2loopback working (guide, section 5.3) |

| Phase | Deliverable | Tests |
|---|---|---|
| F0 | Plan, decisions, this document | done |
| F1 | Android project from the sample, registration, connection, Compose UI with pilot view, screen kept on. `ssl-aggregator` added as a submodule | Stable connection; V11 |
| F2 | Stream listener + phone recording (user start/stop, 7.10) with per-callback metadata and SEI | E1, E2 |
| F3 | NAL parser, RTP packetizer + extension, unicast sender, "Live streaming" toggle. `drone_logger` video path: unicast receiver in `libcommon`, frame assembly, pipeline with `.mkv`, **v4l2loopback injection**, display, optional packet CSV. SSL-Vision reading the virtual camera. `docs/protocol.md`. Low-latency Wi-Fi lock | E4, V19 |
| F4 | Telemetry, events, clock beacons. `drone_logger`: schema `drone`, batch writers, configuration check. `vision_logger`: `t_capture_camera`, `capture_session`, `source`. Grafana panels, `export.sh`. Analysis: beacon fit and live-frame correction (10.2), including the decision on step 3 | E7, E10 |
| F5 | Drone configuration state machine (O3 band, H.265, 4K/60, exposure, WB, colour, subtitles), recording, gimbal | E3 |
| F6 | Foreground service, locks, network callbacks, reconnection, final UI | E5, E6, E9, then E8 |
| F7 (conditional) | Only if E9 shows that live detection is not good enough: 4K replay workflow (9.2) and its time map (10.3) | E8 with replay |
| Later | Multicast transport (if wanted); RFC 7798 packetizer only if the live stream is H.265 | — |

---

## Appendix A. Verified MSDK 5.20.1 signatures

Source: `javap` on `com.dji:dji-sdk-v5-aircraft-provided:5.20.1` (Maven Central).
The key fields are declared in the superclasses `DJICameraKey`, `DJIGimbalKey`,
`DJIFlightControllerKey`, `DJIBatteryKey` and `DJIAirlinkKey`. Inspect those, not
`CameraKey` and the other public key classes, which are thin.

```
com.cySdkyc.clx.Helper (in the dji-sdk-v5-aircraft AAR)
  static void install(Application)

SDKManager.getInstance(): init(Context, InitSettings, SDKManagerCallback), registerApp(),
  isRegistered(), getSDKVersion(), destroy()
InitSettings(SDKLinKMode)            SDKLinKMode.LOCAL
DJISDKInitEvent: START_TO_INITIALIZE, INITIALIZE_COMPLETE

SDKManagerCallback (all abstract)
  onRegisterSuccess(); onRegisterFailure(IDJIError)
  onProductDisconnect(int); onProductConnect(int); onProductChanged(int)
  onInitProcess(DJISDKInitEvent, int); onDatabaseDownloadProgress(long, long)
  onRemoteServerStateUpdated(dji.open.pilot.client.manager.ServerState)
  onRemoteServerCompatibleError(IDJIError)
IDJIError: errorType(), errorCode(), innerCode(), hint(), description()

DJINetworkManager.getInstance().addNetworkStatusListener(IDJINetworkStatusListener)
IDJINetworkStatusListener: void isNetworkAvailable(boolean)

KeyTools.createKey(DJIKeyInfo<T>) : DJIKey<T>
CommonCallbacks.KeyListener<T>: void onValueChange(T oldValue, T newValue)
ProductKey: KeyConnection (Boolean), KeyProductType (ProductType, includes DJI_MINI_3_PRO),
  KeyFirmwareVersion (String), KeySerialNumber (String)
RemoteControllerKey: KeyConnection (Boolean), KeyFirmwareVersion (String),
  KeyFirmwareMatchType (FirmwareMatchType enum: RC_N1_MAVIC_3, DJI_MINI_3_PRO, ...),
  KeySerialNumber (String)
FlightControllerKey: KeyFirmwareVersion (String), KeyWholeDeviceFirmwareVersion (String)
Kotlin extensions in the provided jar (not UXSDK): dji.v5.et.create / listen / get / set

ICameraStreamManager.AvailableCameraUpdatedListener
  void onAvailableCameraUpdated(List<ComponentIndexType>)
  default void onCameraStreamEnableUpdate(Map<ComponentIndexType, Boolean>)
ICameraStreamManager.ScaleType: FIX_XY, CENTER_CROP, CENTER_INSIDE

ICameraStreamManager.ReceiveStreamListener
  void onReceiveStream(byte[] data, int offset, int length, StreamInfo info)

ICameraStreamManager
  addReceiveStreamListener(ComponentIndexType, ReceiveStreamListener)
  removeReceiveStreamListener(ReceiveStreamListener)
  addAvailableCameraUpdatedListener(AvailableCameraUpdatedListener)
  putCameraStreamSurface(ComponentIndexType, Surface, int, int, ScaleType)
  removeCameraStreamSurface(Surface)
  setKeepAliveDecoding(boolean)
  setStreamEncoderBitrate(ComponentIndexType, int) / getStreamEncoderBitrate
  setStreamPriority / getStreamPriority / enableStream
  FrameInfo getAircraftStreamFrameInfo(ComponentIndexType)
  VideoStreamFormat getVideoStreamFormat(ComponentIndexType)
  MediaFormat getMediaFormat(ComponentIndexType)

StreamInfo: getMimeType(), getWidth(), getHeight(), getFrameRate(),
            isKeyFrame(), getPresentationTimeMs(), getSeiList(): List<SEIData>
SEIData:   int seiType; byte[] mSeiData
MimeType:  H264, H265

KeyManager
  <R> void listen(DJIKey<R>, Object holder, KeyListener<R>)
  <R> void listen(DJIKey<R>, Object holder, boolean getOnce, KeyListener<R>)
  <P> void setValue(DJIKey<P>, P, CompletionCallback)
  <R> void getValue(DJIKey<R>, CompletionCallbackWithParam<R>)
  <P,R> void performAction(ActionKey<P,R>, P, CompletionCallbackWithParam<R>)

GimbalKey.KeyRotateByAngle : ActionKey<GimbalAngleRotation, EmptyMsg>
GimbalAngleRotation(mode, pitch, roll, yaw, pitchIgnored, rollIgnored,
                    yawIgnored, duration, jointReferenceUsed, timeout)
GimbalAngleRotationMode: RELATIVE_ANGLE, ABSOLUTE_ANGLE

CameraKey: KeyCameraMode, KeyVideoCodecFormat (VideoCodecFormat: DEFAULT/H264/H265),
  KeyVideoResolutionFrameRate / ...Range (VideoResolutionFrameRate{resolution, frameRate}),
  KeyVideoFileFormat, KeyExposureMode, KeyISO (CameraISO),
  KeyShutterSpeed (CameraShutterSpeed), KeyWhiteBalance (CameraWhiteBalanceInfo),
  KeyCameraWhiteBalanceRange, KeyCameraColor / KeyCameraColorRange (CameraColor),
  KeyCameraFocusMode, KeyAntiFlicker, KeyStartRecord / KeyStopRecord (actions),
  KeyIsRecording, KeyRecordingTime, KeyCameraStorageInfos,
  KeyNewlyGeneratedMediaFile, KeyCameraVideoCaptionEnabled, KeySubtitlesSettable
AirLinkKey: KeyFrequencyBand (FrequencyBand), KeyFrequencyBandRange (List<FrequencyBand>),
  KeyFrequencyPoint, KeyUpLinkQuality, KeyDownLinkQuality, KeyLinkSignalQuality
FrequencyBand: BAND_MULTI, BAND_2_DOT_4G, BAND_5_DOT_8G, BAND_5_DOT_7G, BAND_5_DOT_2G, ...
FlightControllerKey: KeyConnection, KeyAircraftAttitude, KeyAircraftVelocity,
  KeyAircraftLocation3D, KeyUltrasonicHeight, KeyWindSpeed, ...
BatteryKey: KeyChargeRemainingInPercent, KeyVoltage
ComponentIndexType: LEFT_OR_MAIN, RIGHT, UP, FPV, VISION_ASSIST, ...
VideoResolution: RESOLUTION_3840x2160, ...  VideoFrameRate: RATE_59FPS, RATE_60FPS, RATE_PRECISE_60FPS
```

## Appendix B. SSL-Vision facts (RoboCup-SSL/ssl-vision, commit 5321d7b, June 2026)

```
src/app/capture_thread.cpp      pic_raw = capture->getFrame();
                                pic_raw.setTime(GetTimeSec());     // gettimeofday, wall clock
                                d->time = pic_raw.getTime();       // -> t_capture
                                d->time_cam = pic_raw.getTimeCam();// -> t_capture_camera
src/app/plugins/plugin_sslnetworkoutput.cpp
                                set_t_capture(data->time); set_t_capture_camera(data->time_cam) if > 0
src/shared/capture/capturev4l.cpp
                                time_cam = buffer.timestamp.tv_sec*1e9 + tv_usec*1e3   (ns)
                                pixel formats: YUYV, MJPEG; settings: cam idx, framerate, width, height
src/shared/capture/capture_video.cpp
                                cv::VideoCapture(file); time_cam += 1/CAP_PROP_FPS from 0 (s)
CMakeLists.txt                  USE_V4L TRUE by default; needs Qt5, OpenCV (videoio), protobuf, Eigen3
InstallPackagesUbuntu.sh        supports Ubuntu 20.04, 22.04, 24.04 only
```

## References

- `ssl-aggregator`: <https://github.com/LimaBR/ssl-aggregator>
- SSL-Vision: <https://github.com/RoboCup-SSL/ssl-vision>
- v4l2loopback: <https://github.com/umlaeute/v4l2loopback>
- DJI MSDK v5 sample and README: <https://github.com/dji-sdk/Mobile-SDK-Android-V5>
- DJI MSDK v5 API reference: <https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ICameraStreamManager.html>
- RFC 6184 (RTP H.264), RFC 7798 (RTP HEVC), RFC 8285 (RTP header extensions), RFC 8325 (DSCP ↔ WMM)
