# DJI Mini 3 Pro video and telemetry relay

This project turns a DJI Mini 3 Pro into a ground-truth camera for the UGV
terrain-traversability work (Fuzzy Logic and Deep Learning courses, PUC-Rio).
The drone hovers in nadir over the test track while the robot runs.

It extends [`ssl-aggregator`](https://github.com/LimaBR/ssl-aggregator), the
existing capture and analysis system. There, `robot_logger` already records the
robot's 100 Hz telemetry (wheel speeds, IMU, sync LED) and `vision_logger` records
SSL-Vision into PostgreSQL. An offline pipeline then aligns the clocks with the
LED and computes per-wheel slip labels. The drone adds a new camera to that same
pipeline.

| Program | Runs on | Language | Role |
|---|---|---|---|
| Android app (`android/`, this repository) | Phone on the RC-N1 controller (Galaxy S25, Android 15) | Kotlin + DJI MSDK 5.20.1, Jetpack Compose | Receives the encoded H.264 live stream from the MSDK and relays it to the PC over RTP/UDP **without decoding or re-encoding**. Also sends per-frame metadata, drone telemetry, events and clock beacons. Configures the drone (4K/60 H.265 recording on the microSD, O3 radio band, gimbal in nadir). |
| `drone_logger` (planned inside `ssl-aggregator`) | Ubuntu 24.04 workstation, wired to the AP | C++23 + GStreamer, CMake, PostgreSQL | A third logger next to `robot_logger` and `vision_logger`, with the same conventions. It saves the original live bitstream to `.mkv`, **decodes it and injects it live into SSL-Vision** through a v4l2loopback virtual camera, writes frames, telemetry, events and clock beacons to schema `drone`, checks the reported configuration, and can display the video. |

Data flows in one direction only: **phone → PC**. The phone owns every drone
setting. The PC only checks that the incoming data matches the expected
configuration and warns the user if it does not.

SSL-Vision processes the drone video and always runs live, on the same PC as
the loggers. If live detection proves insufficient (experiment E9), the 4K file
from the microSD will also be replayed through SSL-Vision after each flight.

Planned layout of this repository:

```
android/          Android Studio project (Gradle), based on DJI SampleCode-V5
docs/             Specification, protocol, experiment results, environment setup
ssl-aggregator/   git submodule (github.com/LimaBR/ssl-aggregator); drone_logger lives here
```

Clone with the submodule:

```bash
git clone --recurse-submodules <this repository's URL>
```

If the repository was cloned without it, fetch it afterwards from the repository root:

```bash
git submodule update --init --recursive
```

## Documentation

- [Specification](docs/specification.md): requirements, architecture, protocol outline, tests and phases.
- [Phone → PC protocol](docs/protocol.md): RTP/H.264 live video and its header extension.
- [Development environment setup](docs/dev-environment.md): Ubuntu, Android Studio, the C++ toolchain, GStreamer, the phone, and Claude Code.
- [CLAUDE.md](CLAUDE.md): working rules for Claude Code in this repository.
- [.env.example](.env.example): the `DRONE_*` configuration keys for `drone_logger`, to be added to `ssl-aggregator`'s `.env`.

## Registering the app with DJI (required before the first build)

The MSDK refuses to work until the app is registered. Registration ties an
**App Key** to one Android **package name** (`applicationId`), so choose the
package name first and do not change it afterwards.

Package name: **`com.rufino.djirelay`**.

> The steps below follow the DJI developer portal as it worked for MSDK v5.
> The portal changes from time to time; if a screen looks different, follow
> the portal and update this section.

### 1. Create a DJI developer account

1. Open <https://developer.dji.com> and choose **Sign Up** (or log in with an existing DJI account).
2. Confirm the e-mail address through the link DJI sends.
3. In the user menu, complete the developer profile if the portal asks for it ("Become a developer").

### 2. Create the app and get the App Key

1. Go to **User Center → Apps → Create App**.
2. Fill in the form:
   - **App Type:** Mobile SDK
   - **App Name:** for example `DJI Relay`
   - **Software Platform:** Android
   - **Package Name:** `com.rufino.djirelay`, exactly the `applicationId` of the project
   - **Category** and **Description:** any short text (for example "Research: live video relay for UGV experiments")
3. Submit. DJI sends an activation e-mail; click the link to activate the app.
4. Back in **User Center → Apps**, open the app and copy the **App Key**.

### 3. Put the App Key in the project

The key must never be committed. Store it in your **user** Gradle properties
file, `~/.gradle/gradle.properties` (create the file if it does not exist), as one line:

```
DJI_API_KEY=<your App Key>
```

The build injects it into the manifest as the meta-data entry
`com.dji.sdk.API_KEY`, the same mechanism the DJI sample uses
(`manifestPlaceholders`).

### 4. First run

- The first registration needs **internet access**. The dedicated Wi-Fi AP has internet, so registration happens on the first launch with the phone connected to it.
- The phone must be connected to the RC-N1 by USB, and **DJI Fly must be closed**: only one app at a time can use the controller.
- The app shows "SDK registered" when registration succeeds. If it fails, the error code is shown and logged. The usual causes are a package name that differs from the registered one, a missing key, or no internet.
