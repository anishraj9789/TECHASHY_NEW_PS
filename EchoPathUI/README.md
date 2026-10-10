# EchoPath

EchoPath is a native Android application that utilizes high-frequency acoustic digital signal processing (DSP) to act as a spatial radar. It emits an inaudible 18.5kHz carrier tone and analyzes the microphone's input in real-time to detect the Doppler shift of sound bouncing off nearby obstacles. It provides immediate haptic and audio feedback to help users navigate their surroundings.

## Features
- **Acoustic Radar Engine:** Real-time 44.1kHz audio processing using a custom, zero-allocation Cooley-Tukey FFT algorithm written purely in Kotlin.
- **Doppler Shift Analysis:** Detects approaching objects by analyzing high-frequency sideband energy against a dynamically adjusting Exponential Moving Average (EMA) baseline.
- **Hardware Actuation:** Fires native hardware haptic vibrations (`VibratorManager`) and low-latency audio ticks (`ToneGenerator`) scaling in intensity based on obstacle proximity (CLEAR, CAUTION, WARNING, CRITICAL).
- **Edge AI Ready:** Pre-configured with **TensorFlow Lite**. The architecture currently includes a heuristic classifier that distinguishes between "Hard Surfaces" (walls) and "Soft Objects" (people/furniture) based on echo dispersion, ready to be swapped with a trained `.tflite` CNN.
- **Jetpack Compose UI:** A beautiful, responsive, dark-mode native UI featuring glowing animations, live telemetry data feeds, and persistent settings storage.

## Tech Stack
- **Platform:** Native Android (Kotlin)
- **UI Toolkit:** Jetpack Compose (Material 3)
- **DSP/Audio:** `AudioRecord` (UNPROCESSED), `AudioTrack`, Custom FFT
- **Machine Learning:** TensorFlow Lite

## Installation & Deployment
1. Clone this repository to your local machine.
2. Open the project folder in **Android Studio**.
3. Let Gradle sync and download dependencies (including Jetpack Compose and TensorFlow Lite).
4. Connect an Android device (API 26+) via USB or wireless debugging.
5. Click **Run** (`Shift + F10`).

> **Note:** The app requires `RECORD_AUDIO` and `VIBRATE` permissions to function. The app bypasses Android's default noise-cancellation to ensure high frequencies (18.5kHz) are not filtered out. 

## How it Works
1. **Emit:** The phone's loudspeaker continuously plays an 18.5kHz sine wave.
2. **Listen:** The microphone records raw PCM audio in 2048-sample chunks.
3. **Calculate:** A Hanning window is applied, and an FFT transforms the audio into the frequency domain.
4. **Detect:** If an object is moving towards the phone, the sound waves compress, shifting the 18.5kHz reflection slightly higher.
5. **Classify & Alert:** The engine detects this shift, classifies the obstacle type, and triggers the phone's vibration motor and speaker to alert the user.

## Future Roadmap
- Swap the current heuristic sideband classifier with a trained 1D-CNN TensorFlow Lite model for highly accurate material classification.
- Implement Recurrent Neural Networks (RNN) for intelligent background noise cancellation.
- Add trajectory prediction for fast-moving obstacles.

## License
MIT License
