# Development environment

One Ubuntu workstation hosts both toolchains. The workstation currently runs
**Ubuntu 24.04 LTS** (24.04.5), and this guide is written for it. Steps that differ
from the Ubuntu defaults on 24.04 are marked **(24.04)**: GCC 14 as an extra compiler,
libpqxx 7.10 built from source, and the JDK used by Gradle.

All repositories live under `~/PUC/`:

| Path | What |
|---|---|
| `~/PUC/dji-relay` | This repository, with the `ssl-aggregator` submodule |
| `~/PUC/ssl-vision` | SSL-Vision |
| `~/PUC/libpqxx` | libpqxx source, built and installed to `/usr/local` (section 3.1) |

| Purpose | Tool |
|---|---|
| Android app (Kotlin, Gradle, DJI MSDK) | Android Studio |
| `drone_logger` and the rest of `ssl-aggregator` (C++23, CMake, GStreamer, libpqxx) | VS Code + GCC 14 or newer |
| Database and dashboards | PostgreSQL, Grafana (optional), DBeaver (optional) |
| Offline analysis (`ssl-aggregator/analysis`) | Python 3 virtual environment |
| AI assistant | Claude Code: VS Code extension, CLI, or the Android Studio plugin |
| Target phone | Samsung Galaxy S25, Android 15 |
| Vision | SSL-Vision (live, reading the drone through a v4l2loopback virtual camera) |
| GPU | NVIDIA RTX 5080 (hardware H.264 decoding for SSL-Vision injection and the live display) |

Versions pinned by the DJI sample (SampleCode-V5, MSDK 5.20.1): Android Gradle
Plugin 8.7.0, Gradle 8.12, Kotlin 2.1.0, NDK 28.0.13004108, compileSdk/targetSdk 35,
minSdk 24, ABI `arm64-v8a` only. Gradle 8.12 must run on a **full JDK 17–23**. The JDK
that comes with current Android Studio (JBR 25) is too new for Gradle 8.12, and
Ubuntu's default `java` is only a runtime (JRE). Section 6 installs JDK 21.

Each step below says what to run and why. Run the commands one at a time and read
the output before moving on.

---

## 1. Base packages

These are the compiler, build tools, debugger and Git.

```bash
sudo apt update
```

```bash
sudo apt install -y git curl build-essential cmake ninja-build pkg-config gdb
```

Check the versions. CMake must be 3.20 or newer. GCC must be **14 or newer**,
because `ssl-aggregator` is built as C++23 (`std::print`, `std::format`,
`std::jthread`).

```bash
cmake --version
```

```bash
g++ --version
```

### 1.1 GCC 14 (24.04)

The default `g++` on Ubuntu 24.04 is GCC 13, which lacks `std::print`. Install GCC 14
next to it. The default compiler stays GCC 13, so every C++23 build must ask for
`g++-14` explicitly.

1. Install it:

   ```bash
   sudo apt install -y g++-14
   ```

2. Check it (expect `14.2`):

   ```bash
   g++-14 --version
   ```

3. **Command line:** pass the compiler when a build directory is configured for the
   first time. CMake remembers it in `build/CMakeCache.txt` (`CMAKE_CXX_COMPILER`), so
   later builds use it too. To switch compilers, delete the build directory first:

   ```bash
   cmake -S . -B build -DCMAKE_CXX_COMPILER=g++-14
   ```

4. **VS Code (CMake Tools):** open the Command Palette (`Ctrl+Shift+P`), run
   **CMake: Scan for Kits**, then **CMake: Select a Kit** and choose
   **GCC 14.2.0 x86_64-linux-gnu**, not GCC 13.3.0. The kit is saved per workspace. The
   status bar shows the active kit.

---

## 2. NVIDIA driver (RTX 5080)

RTX 50-series cards (Blackwell) need a recent driver with the **open** kernel
modules. Ubuntu's tool chooses the right one.

1. List the recommended driver:

   ```bash
   ubuntu-drivers list
   ```

2. Install it, then reboot:

   ```bash
   sudo ubuntu-drivers install
   ```

3. After the reboot, check that the driver is loaded and sees the card:

   ```bash
   nvidia-smi
   ```

The video-decoding library (`libnvcuvid`, from the `libnvidia-decode-<version>`
package) normally comes with the driver metapackage. GStreamer's `nvh264dec` needs
it. Section 3 shows how to check.

---

## 3. GStreamer, FFmpeg and libraries for `drone_logger`

- `libgstreamer1.0-dev` and `libgstreamer-plugins-base1.0-dev`: headers for building against GStreamer (including `appsrc`).
- `gstreamer1.0-plugins-good`: RTP (`rtpjitterbuffer`, `rtph264depay`) and Matroska (`matroskamux`).
- `gstreamer1.0-plugins-bad`: `h264parse` and the NVIDIA decoder `nvh264dec`.
- `gstreamer1.0-plugins-ugly` and `gstreamer1.0-libav`: extra codecs and `avdec_h264` (software fallback).
- `gstreamer1.0-tools`: `gst-launch-1.0` and `gst-inspect-1.0` for testing.
- `ffmpeg`: provides `ffprobe` and `ffplay`, used to analyse the captures of experiment E1.
- `nlohmann-json3-dev`: header-only JSON library, used to parse the phone's messages.
- `libpq-dev`: the PostgreSQL C client library. libpqxx, the C++ client `ssl-aggregator` uses, is built on top of it from source (section 3.1).
- `protobuf-compiler` and `libprotobuf-dev`: required to build `ssl-aggregator`'s `vision_logger`.

```bash
sudo apt install -y libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-libav gstreamer1.0-tools ffmpeg nlohmann-json3-dev libpq-dev protobuf-compiler libprotobuf-dev
```

### 3.1 libpqxx 7.10 from source, in C++23 (24.04)

`ssl-aggregator` uses libpqxx 7.10 (its README describes a 7.10 exit-time issue that
the loggers work around with `std::quick_exit`). Ubuntu 24.04's `libpqxx-dev` is
7.8.1, so **do not install `libpqxx-dev` from apt**. Build 7.10.7 instead, with the same
compiler (`g++-14`) and C++ standard (23) as `ssl-aggregator`, so the library and the
loggers agree on the C++ standard library features they use.

1. Get the source at tag `7.10.7`:

   ```bash
   git clone --branch 7.10.7 --depth 1 https://github.com/jtv/libpqxx.git ~/PUC/libpqxx
   ```

2. Configure a shared Release build with GCC 14 and C++23, without the library's own tests:

   ```bash
   cmake -S ~/PUC/libpqxx -B ~/PUC/libpqxx/build -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=g++-14 -DCMAKE_CXX_STANDARD=23 -DBUILD_SHARED_LIBS=ON -DSKIP_BUILD_TEST=ON
   ```

3. Build it:

   ```bash
   cmake --build ~/PUC/libpqxx/build -j
   ```

4. Install it to `/usr/local` (the default prefix), then refresh the dynamic linker cache:

   ```bash
   sudo cmake --install ~/PUC/libpqxx/build
   ```

   ```bash
   sudo ldconfig
   ```

5. Check that `pkg-config`, which `ssl-aggregator`'s CMake uses, finds 7.10.7 in `/usr/local`:

   ```bash
   pkg-config --modversion libpqxx
   ```

   ```bash
   pkg-config --variable=prefix libpqxx
   ```

Checks:

1. GStreamer version (expect 1.2x):

   ```bash
   gst-launch-1.0 --version
   ```

2. Elements `drone_logger` needs. Each command must print the element's details, not "No such element":

   ```bash
   gst-inspect-1.0 rtpjitterbuffer
   ```

   ```bash
   gst-inspect-1.0 rtph264depay
   ```

   ```bash
   gst-inspect-1.0 matroskamux
   ```

3. NVIDIA hardware decoder. If this fails while `nvidia-smi` works, the `libnvidia-decode-<version>` package is missing. `drone_logger` will still work with the software decoder `avdec_h264`:

   ```bash
   gst-inspect-1.0 nvh264dec
   ```

4. Display test. A window with a test pattern should open:

   ```bash
   gst-launch-1.0 videotestsrc ! autovideosink
   ```

---

## 4. PC and AP network settings

- **PC on Ethernet** to the AP, with a **fixed IP** (DHCP reservation in the AP's settings). The phone sends to this address.
- **AP radios:**
  - 5 GHz on channel 36, 80 MHz, for the phone.
  - 2.4 GHz kept on for the robot, because the ESP8266 has no 5 GHz radio.
- **Robot multicast interface:** if the PC has more than one network interface (for example Ethernet plus Wi-Fi), set `ROBOT_MCAST_IFACE` in `ssl-aggregator`'s `.env` to the **IP address of the Ethernet interface**. Otherwise the multicast join may go out through the wrong interface (see "Multi-homed host" in `ssl-aggregator`'s README). The interface addresses are shown by:

  ```bash
  ip -br addr
  ```

- **Firewall:** if `ufw` is enabled (`sudo ufw status`), allow the drone's UDP ports and the existing multicast ports:

  ```bash
  sudo ufw allow 5600:5601/udp
  ```

  ```bash
  sudo ufw allow 10001/udp
  ```

  ```bash
  sudo ufw allow 10006/udp
  ```

---

## 5. ssl-aggregator, SSL-Vision and the virtual camera

### 5.1 Clone this repository with its submodule

`ssl-aggregator` is a git submodule of this repository (at `ssl-aggregator/`),
and `drone_logger` lives inside it. Clone both at once (replace the URL with this
repository's):

```bash
git clone --recurse-submodules <this repository's URL> ~/PUC/dji-relay
```

Then set two git options once, so that `git pull` also updates the submodule and
`git push` also pushes any submodule commits that the parent refers to:

```bash
git -C ~/PUC/dji-relay config submodule.recurse true
```

```bash
git -C ~/PUC/dji-relay config push.recurseSubmodules on-demand
```

### 5.2 Database, build and analysis

`ssl-aggregator`'s README is the reference for each step. Run them inside
`~/PUC/dji-relay/ssl-aggregator`:

1. **PostgreSQL:** install and start it as described in "Install PostgreSQL" (`sudo apt install postgresql`, then enable the service).

2. **Database:** copy `.env.example` to `.env`, set `PGPASSWORD` and `GRAFANA_PASSWORD`, then run `./scripts/db_init.sh` ("Database setup"). It creates the `robot_data` database, its schemas and roles.

3. **Build** the loggers. This also confirms that the C++23 toolchain and libpqxx work. On 24.04, skip the README's `sudo apt install libpqxx-dev` (libpqxx comes from section 3.1) and configure with GCC 14 (section 1.1):

   ```bash
   cmake -S . -B build -DCMAKE_CXX_COMPILER=g++-14
   ```

   ```bash
   cmake --build build -j
   ```

4. **Grafana and DBeaver** (optional): follow "Optional: data inspection".

5. **Python analysis environment:** create `.venv` in the `ssl-aggregator` root, which is the path the analysis scripts expect:

   ```bash
   sudo apt install -y python3-venv
   ```

   ```bash
   python3 -m venv ~/PUC/dji-relay/ssl-aggregator/.venv
   ```

   ```bash
   ~/PUC/dji-relay/ssl-aggregator/.venv/bin/pip install -r ~/PUC/dji-relay/ssl-aggregator/analysis/requirements.txt
   ```

   `analysis/requirements.txt` lists only the Stage 1–4 packages. The Stage 5 models also import `torch`, `optuna`, `xgboost` and `scikit-learn`:

   ```bash
   ~/PUC/dji-relay/ssl-aggregator/.venv/bin/pip install torch optuna xgboost scikit-learn
   ```

### 5.3 v4l2loopback (the virtual camera SSL-Vision reads)

`drone_logger` writes the decoded live video to a virtual camera, and SSL-Vision
reads it like a webcam.

1. Install the kernel module and the V4L tools:

   ```bash
   sudo apt install -y v4l2loopback-dkms v4l2loopback-utils v4l-utils
   ```

   With **Secure Boot** enabled, the installer asks you to choose a password to enroll a signing key (MOK). On the next reboot, a blue "MOK management" screen appears: choose **Enroll MOK**, confirm, and type that password. Without this step the module will not load.

2. Load it with a fixed device number (10) and a name:

   ```bash
   sudo modprobe v4l2loopback video_nr=10 card_label="DJI drone" exclusive_caps=1 max_buffers=4
   ```

   `exclusive_caps=1` makes the device behave like a real camera towards readers, which some programs need. With it, the device presents itself as a camera only while a writer (`drone_logger`) is active, so start the capture in SSL-Vision after the video is flowing.

   `max_buffers=4` is required: SSL-Vision's V4L module asks for 3 buffers, and with the default of 2 it fails with `REQBUFS returned error, count 2`. To change the options of a loaded module, close every program using `/dev/video10` (`fuser /dev/video10` lists them), then run `sudo modprobe -r v4l2loopback && sudo modprobe v4l2loopback` after editing the file of step 3; `cat /sys/module/v4l2loopback/parameters/max_buffers` shows the value in use.

3. Load it on every boot with the same options. Create `/etc/modules-load.d/v4l2loopback.conf` with the single line:

   ```
   v4l2loopback
   ```

   and `/etc/modprobe.d/v4l2loopback.conf` with the line:

   ```
   options v4l2loopback video_nr=10 card_label="DJI drone" exclusive_caps=1 max_buffers=4
   ```

4. Check that `/dev/video10` appears with the name "DJI drone":

   ```bash
   v4l2-ctl --list-devices
   ```

5. Test it end to end with two terminals. In the first, write a YUYV test pattern into the device, at the live stream's resolution (1440×1080 measured in F2). v4l2loopback 0.12 needs the caps to state progressive and colorimetry `2:4:7:1`; `videotestsrc` negotiates them by itself:

   ```bash
   gst-launch-1.0 videotestsrc ! video/x-raw,format=YUY2,width=1440,height=1080,framerate=30/1 ! v4l2sink device=/dev/video10
   ```

   In the second, read it back. A window with the test pattern should open:

   ```bash
   gst-launch-1.0 v4l2src device=/dev/video10 ! videoconvert ! autovideosink
   ```

   Stop both with `Ctrl+C`.

### 5.4 SSL-Vision

Skip this if SSL-Vision is already installed and works on this PC. Make sure it
was built with V4L support, which is the default.

1. Clone it:

   ```bash
   git clone https://github.com/RoboCup-SSL/ssl-vision.git ~/PUC/ssl-vision
   ```

2. Install its dependencies. **(24.04)** Its `InstallPackagesUbuntu.sh` script supports Ubuntu 24.04, so run it. It calls `sudo apt-get` itself and asks for the password:

   ```bash
   cd ~/PUC/ssl-vision && ./InstallPackagesUbuntu.sh
   ```

   On a newer Ubuntu (for example 26.04) the script refuses to run, because it only knows up to 24.04. Install the same packages by hand instead:

   ```bash
   sudo apt install -y g++ cmake libeigen3-dev freeglut3-dev libopencv-dev qtdeclarative5-dev libqt5multimedia5 protobuf-compiler libprotobuf-dev libdc1394-dev libv4l-0
   ```

   If apt reports that a Qt5 package does not exist there, stop and note it: that is open item V20 in the specification.

3. Build it (a Release build through CMake, with V4L on by default):

   ```bash
   make -C ~/PUC/ssl-vision
   ```

4. Run it with capture started. Add `-c 2` if a second camera (for example the ceiling camera indoors) runs together with the drone:

   ```bash
   cd ~/PUC/ssl-vision && LC_NUMERIC=en_US.UTF-8 ./bin/vision -s
   ```

5. In the drone's camera thread, choose the **V4L** capture module and set: `cam idx` 10 (it opens `/dev/video10`), `width` 1920, `height` 1080, `framerate` 30, `pixel format` YUYV. With the test pattern of 5.3 step 5 running, SSL-Vision should show it.

---

## 6. Android Studio

1. Install Android Studio. The snap is the simplest option:

   ```bash
   sudo snap install android-studio --classic
   ```

   Another option is to download the `.tar.gz` from <https://developer.android.com/studio>, extract it to `/opt/android-studio` and run `bin/studio.sh`.

2. On first launch, the setup wizard installs the Android SDK, by default in `~/Android/Sdk`. Accept the standard setup.

3. Open **Settings → Languages & Frameworks → Android SDK**:
   - **SDK Platforms** tab: tick **Android 15.0 (API 35)**.
   - **SDK Tools** tab: tick **Show Package Details**, then select:
     - **Android SDK Build-Tools** 35.x
     - **NDK (Side by side)** **28.0.13004108** (the exact version the DJI sample pins)
     - **Android SDK Platform-Tools** (`adb`)
   - Click **Apply**.

4. Make `adb` available in the terminal: add `~/Android/Sdk/platform-tools` to `PATH` in `~/.bashrc`, open a new terminal and check:

   ```bash
   adb version
   ```

5. **JDK for Gradle (24.04).** The project pins Gradle 8.12 (from the DJI sample), which runs on Java 17–23 only. Two Java installations are already present, and neither works:
   - Android Studio's bundled JDK (JetBrains Runtime) is **Java 25**. The build fails with `Unsupported class file major version 69`.
   - Ubuntu's default `java` (`openjdk-21-jre`) is only a runtime, without a compiler. The build fails with `Toolchain installation '/usr/lib/jvm/java-21-openjdk-amd64' does not provide the required capabilities: [JAVA_COMPILER]`.

   Install the full JDK 21 and point both Gradle and Android Studio to it:

   1. Install it (it adds `javac`, `javap` and the other JDK tools to the existing Java 21):

      ```bash
      sudo apt install -y openjdk-21-jdk
      ```

   2. Check that the compiler is there (expect `javac 21.x`):

      ```bash
      /usr/lib/jvm/java-21-openjdk-amd64/bin/javac -version
      ```

   3. **Command-line builds:** add this line to the **user** file `~/.gradle/gradle.properties`, next to `DJI_API_KEY` (never to the project's `android/gradle.properties`, which is committed):

      ```
      org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
      ```

   4. **Android Studio:** open the `android/` folder, then **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** and choose the JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (add it with **Add JDK from disk** if it is not listed). Do not use the bundled JetBrains Runtime. If Android Studio offers to upgrade the Android Gradle Plugin, decline: the versions are pinned by the DJI sample.

      Two pitfalls:
      - **Gradle JDK is a per-project setting.** Open the project first (**File → Open → `~/PUC/dji-relay/android`**, the folder with `settings.gradle`, not the repository root). Set from the welcome screen, with no project open, the choice is not saved and the field returns to JBR 25.
      - **Do not put the JDK path in Gradle user home.** That field, on the same screen, must stay empty (default `~/.gradle`). If it points elsewhere, Android Studio no longer reads `~/.gradle/gradle.properties`, so it loses `DJI_API_KEY` and `org.gradle.java.home`, and the sync fails.

      The choice is stored in `android/.idea/gradle.xml` (`gradleJvm`), which is not committed.

6. **SDK location for command-line builds.** Android Studio writes `android/local.properties` with the SDK path when it opens the project. To build from a terminal before that, create the file with this single line (it is machine-specific and ignored by git):

   ```
   sdk.dir=/home/<user>/Android/Sdk
   ```

   Without it, `./gradlew` fails with `SDK location not found`.

The Android emulator is not useful here: the MSDK needs the real controller over USB.

---

## 7. Galaxy S25 setup

### 7.1 Developer options and wireless debugging

The phone's only USB-C port is connected to the RC-N1, so **`adb` has to run over
Wi-Fi** (wireless debugging, Android 11+).

1. **Settings → About phone → Software information** → tap **Build number** seven times to enable Developer options.
2. **Settings → Security and privacy → Auto Blocker**: turn it **off**. When on, it blocks USB commands and installing apps from outside the Galaxy Store, which includes `adb install`.
3. **Settings → Developer options**: turn on **USB debugging** and **Wireless debugging**.
4. Pair the PC with the phone (once). Phone and PC must be on the same AP.
   1. On the phone: **Wireless debugging → Pair device with pairing code**. The phone shows an IP:port and a six-digit code.
   2. On the PC, run the command with that IP:port, then type the code:

      ```bash
      adb pair 192.168.1.50:37123
      ```

5. Connect. The **Wireless debugging** screen shows another IP:port, different from the pairing port:

   ```bash
   adb connect 192.168.1.50:41234
   ```

6. Check that the phone appears:

   ```bash
   adb devices
   ```

Android Studio sees the phone after this and can install and debug the app over Wi-Fi.
Turn wireless debugging off during the latency experiments, so its traffic does not
share the channel with the video.

> **Warning: duplicate adb connection.** Once the phone is paired, `adb` also finds it
> by itself on the network (mDNS) and connects automatically. It then appears as
> `adb-<serial>-<id>._adb-tls-connect._tcp`. If you also run `adb connect <IP>:<port>`,
> the same phone is listed **twice** in `adb devices`. Then `adb install`, `adb logcat`
> and the other commands fail with `more than one device/emulator`, and Android Studio
> shows two devices.
>
> Keep only one connection. Usually the automatic one is enough, and `adb connect` is
> not needed at all. If both are listed, drop the manual one (use the IP:port shown in
> `adb devices`):
>
> ```bash
> adb disconnect 192.168.1.50:41234
> ```
>
> The connection port changes every time wireless debugging is turned on again, so an
> old `IP:port` entry can also remain listed as `offline`. Remove it the same way.
> The automatic entries are listed by:
>
> ```bash
> adb mdns services
> ```

### 7.2 Settings that affect the relay

- **Battery:** **Settings → Battery → Background usage limits → Never sleeping apps** → add the app. Also set the app's battery usage to **Unrestricted** (**Settings → Apps → <app> → Battery**).
- **Wi-Fi:** turn off **Switch to mobile data** (**Settings → Connections → Wi-Fi → ⋮ → Intelligent Wi-Fi**). Turn off mobile data on this phone, since it is dedicated to this job.
- **DJI Fly:** it may stay installed, but it must be **closed** while the relay app runs. Only one app at a time can use the RC-N1.

---

## 8. VS Code

1. Install VS Code. Either the snap:

   ```bash
   sudo snap install code --classic
   ```

   or the `.deb` from <https://code.visualstudio.com>, which also adds Microsoft's apt repository for updates.

2. Install the extensions from the Extensions view (`Ctrl+Shift+X`):
   - **C/C++** (`ms-vscode.cpptools`): IntelliSense and debugging with GDB
   - **CMake Tools** (`ms-vscode.cmake-tools`): configure and build from VS Code. `ssl-aggregator` already ships `.vscode/` with a build task and GDB launch configurations for its loggers
   - **Claude Code** (`anthropic.claude-code`): see section 9

Use Android Studio, not VS Code, for the Kotlin app: Gradle sync, the Android
manifest tools and the device debugger only work well there.

---

## 9. Claude Code on this workstation

Yes, Claude Code runs on this machine (Ubuntu 20.04 or newer is supported), and the
VS Code extension is the most convenient way to use it. It requires a paid Claude
plan (Pro, Max, Ultra, Team or Enterprise) or a Claude Console account. The free
plan does not include Claude Code.

### 9.1 VS Code extension (preferred)

1. Requires VS Code 1.94 or newer (**Help → About**).
2. In the Extensions view, search for **Claude Code** (publisher Anthropic) and click **Install**.
3. Open the Claude Code panel: the spark icon in the editor toolbar (with a file open), the Activity Bar, or **✻ Claude Code** in the status bar.
4. Click **Sign in** and finish the login in the browser.

The extension has its own copy of Claude Code for the chat panel, but it does **not**
add the `claude` command to `PATH`. To use Claude Code in a terminal as well (or in
Android Studio), also install the CLI.

### 9.2 CLI

1. Install with the official installer, which updates itself automatically:

   ```bash
   curl -fsSL https://claude.ai/install.sh | bash
   ```

   An apt repository is also available, documented at <https://code.claude.com/docs/en/setup>.

2. Open a new terminal and check:

   ```bash
   claude --version
   ```

3. Start it from this repository's root and log in on the first run. The `ssl-aggregator` submodule is inside the repository, so Claude Code sees both projects. SSL-Vision, if it is needed, is added as a second working directory with `--add-dir`:

   ```bash
   cd ~/PUC/dji-relay && claude --add-dir ~/PUC/ssl-vision
   ```

   In the VS Code extension, open `~/PUC/dji-relay` as the folder. Add SSL-Vision with the `/add-dir ~/PUC/ssl-vision` command in a session, only if it is needed.

### 9.3 Manual permission mode

In this project Claude Code works in **Manual** mode: it asks before every command
and file change. The mode is set once in two places, so the CLI and the VS Code
extension both start in it.

1. **CLI and all sessions:** in the user settings file `~/.claude/settings.json`, set
   `permissions.defaultMode` to `"default"`, which is the internal name of the Manual
   mode. Create the file if it does not exist. If it already exists, add only the
   `defaultMode` key and keep the other keys:

   ```json
   {
     "permissions": {
       "defaultMode": "default"
     }
   }
   ```

2. **VS Code extension:** open **File → Preferences → Settings**, search for
   `claudeCode.initialPermissionMode` and choose `default`. Or add it to the user
   `settings.json` of VS Code:

   ```json
   "claudeCode.initialPermissionMode": "default"
   ```

The current mode is shown in the session's mode indicator. Do not switch to the modes
that skip prompts (`acceptEdits`, `auto`, `dontAsk`, `bypassPermissions`) in this
project.

### 9.4 Android Studio plugin (optional)

The Claude Code plugin for JetBrains IDEs supports Android Studio. Install the CLI
(9.2) first, then install the **Claude Code** plugin from **Settings → Plugins →
Marketplace** and restart. The plugin starts `claude` in Android Studio's terminal,
shows the changes in the IDE's diff viewer and reads its error diagnostics.

### 9.5 Why run it locally

Running locally, with the phone paired over wireless `adb` and `drone_logger` on the
same PC, Claude Code can run each step itself and check the results:

- `./gradlew assembleDebug` and `adb install`
- reading `adb logcat`
- building and running `drone_logger` next to `robot_logger`
- querying PostgreSQL and running the `ssl-aggregator` analysis on the E1–E8 captures

A cloud session cannot do this, because it has no access to the phone, the controller or the drone.

Safety rules for this project:

- Keep Claude Code in **Manual** mode (section 9.3), so it asks before every command and change.
- Do every bench test with the **propellers removed**.
- The app only configures the camera and gimbal and starts and stops recording. It never sends flight commands.
