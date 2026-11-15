# CLAUDE.md

Instructions for Claude Code working in this repository.

## Project

A relay of DJI Mini 3 Pro live video and telemetry to a Linux PC. The drone is a
ground-truth camera for UGV slip and traversability work (PUC-Rio). The full,
current specification is [docs/specification.md](docs/specification.md). Read it
before any work.

- `android/`: Kotlin app, DJI MSDK 5.20.1, Jetpack Compose UI, package `com.rufino.djirelay`.
- `docs/`: specification, protocol, experiment results, environment setup.
- `ssl-aggregator/`: git submodule → [`LimaBR/ssl-aggregator`](https://github.com/LimaBR/ssl-aggregator), branch `main`. The PC side, `drone_logger`, lives inside it (D6).
- SSL-Vision processes the drone video and always runs live. `drone_logger` injects the decoded live video into it through v4l2loopback (D7, spec section 9).

## Working with the submodule

- Before editing inside `ssl-aggregator/`, check out a branch there (`git switch main`). A freshly updated submodule is on a detached HEAD, and commits made there are easily lost.
- Commit and **push inside `ssl-aggregator/` first**. Then commit the updated submodule pointer in this repository. Never push a pointer to a commit that exists only locally.
- A change to the phone ↔ PC protocol touches both repositories. Update `docs/protocol.md` here in the same change.

## ssl-aggregator: existing system to extend, not duplicate

- `robot_logger` already records the robot's telemetry (`robot.telemetry`), and `vision_logger` records SSL-Vision (`vision.*`). Do not re-implement them.
- Follow its conventions in `drone_logger`:
  - C++23 (`gnu++23`, GCC 14+), `-Wall -Wextra -Wpedantic`.
  - `libcommon` for the `.env` loader, `now_rx()`, `BlockingQueue` and `BatchWriter`.
  - `rx_wall` + `rx_monotonic` + `capture_session` on every row; no `ON CONFLICT` and no silent drops.
  - A health line to stderr once per second; `std::quick_exit` after flushing (the libpqxx 7.10 issue).
- Time alignment is done offline in `analysis/` (Stage 1, LED edges ↔ camera capture time). Drone data must give the analysis what it needs: spec section 9.
- Changes to `ssl-aggregator` (SQL, scripts, Grafana, analysis) are planned in the spec (section 8.1). Make them only in the phase that lists them, after discussing them.

## Working agreement

- **Discuss before coding.** Present the plan and the open questions first. Generate code only after agreement, and only when the user asks for it.
- **Work in phases** (spec section 12). Each phase ends with its acceptance tests. Wait for the user's validation before starting the next phase.
- **Never invent APIs.** Check every MSDK call against SampleCode-V5 (<https://github.com/dji-sdk/Mobile-SDK-Android-V5>) or the signatures in `com.dji:dji-sdk-v5-aircraft-provided:5.20.1`. To inspect them, download the `.jar` from Maven Central and run `javap` on the classes. The key fields are declared in `DJICameraKey`, `DJIGimbalKey`, `DJIFlightControllerKey` and `DJIBatteryKey`, not in the thin `CameraKey` and similar classes. If an API is not as described, stop and ask.
- **Data flows phone → PC only.** The PC never configures or commands the phone.
- **No re-encoding** of video, ever.
- **Shell commands:** describe them and explain each step. Do not write shell scripts unless asked.
- **Language:** chat with the user in Portuguese. Write code, comments, UI text and every `.md` file in English.
- Secrets (`DJI_API_KEY`, `.env`) are never committed.

## Hardware safety

- Bench tests are done with the **propellers removed**.
- The app never sends flight commands. It only configures the drone (camera, gimbal, radio band), and starts and stops recording.

## Environment

See [docs/dev-environment.md](docs/dev-environment.md). The phone's USB-C port is
connected to the RC-N1, so `adb` runs over wireless debugging.
