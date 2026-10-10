# EchoPath

EchoPath is a native Android application that implements a **Continuous-Wave Doppler Acoustic Radar** engine. It uses inaudible high-frequency sound (18.5kHz) to detect approaching objects, walls, or obstacles in real-time by analyzing Doppler shift frequencies.

This repository contains the headless, production-ready Digital Signal Processing (DSP) core, ready to be attached to a visual UI.

## Features

- **Native Android Audio APIs:** Utilizes low-level `AudioTrack` and `AudioRecord` APIs to bypass OS-level noise cancellation and auto-gain control, ensuring pure signal processing.
- **Custom DSP Engine:** Includes a highly optimized, in-place Cooley-Tukey Fast Fourier Transform (FFT) algorithm written entirely in Kotlin to calculate Doppler sidebands in real-time at 60fps.
- **Multi-Tier Hazard Evaluation:** Dynamic sensitivity thresholding that categorizes threats into CLEAR, CAUTION, or CRITICAL tiers.
- **Zero-Latency Actuation:** Instantaneous haptic feedback (using Android's `Vibrator`) and in-ear audio ticks based on the proximity of approaching obstacles.
- **Simulation Fallback:** A built-in synthetic physics engine simulating realistic approach speeds and Doppler blooms if microphone permissions are denied or hardware is unavailable.

## Architecture

The core of the application resides in:
`app/src/main/java/com/echopath/engine/AcousticRadarEngine.kt`

The engine operates on a background thread and streams a `RadarTelemetry` object via a callback interface. It is completely decoupled from any UI frameworks.

## Getting Started

1. **Clone the repository:**
   ```bash
   git clone https://github.com/yourusername/EchoPath.git
   ```
2. **Open in Android Studio:**
   Launch Android Studio, select **Open**, and navigate to the cloned repository folder. Gradle will automatically sync.
3. **Permissions:**
   The app requests `RECORD_AUDIO` and `VIBRATE` permissions at runtime. Ensure you grant them when prompted on your physical device.
4. **Deploy:**
   Connect an Android device (emulators won't capture accurate acoustic data) and click the **Run** button.

## License

MIT License
