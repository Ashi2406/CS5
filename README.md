# TRT Cast — Android prototype

This is the first Android phone-side prototype for TRT Cast.

## What it does
- Requests Android MediaProjection permission.
- Starts a foreground casting service.
- Creates a virtual display from the phone screen.
- Starts a local TCP/HTTP endpoint on port 8080.

## Important
This repository is **not yet a finished Scorpio-N receiver**. Android Auto only exposes supported car-app categories; arbitrary phone-screen mirroring is not a normal Android Auto app capability. A car-side receiver/protocol is required for full-screen mirroring.

The current transport endpoint is intentionally a first-stage scaffold. The next implementation should encode the captured RGBA frames to H.264/WebRTC (or another receiver-compatible protocol) and implement the actual car-side protocol if the vehicle exposes one.

## Build
Open this folder in Android Studio and build the debug APK. The project uses Android Gradle Plugin 8.6.1, Kotlin 2.0.21, compileSdk 35.
