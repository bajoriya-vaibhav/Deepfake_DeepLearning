# Emulator Testing Guide

Since this application requires **Root Access** to capture the screen of *other* apps, testing on a standard Android Emulator requires specific configuration. Standard AVDs (Android Virtual Devices) from Google are not rooted by default and do not have the `su` binary accessible to apps.

Here are the two best ways to test this application on a PC:

## Option 1: Genymotion (Recommended)
Genymotion is a fast Android emulator that includes a simple toggle to enable Root access.

1.  **Download & Install**: [Genymotion Personal Edition](https://www.genymotion.com/download/) (Free for personal use).
2.  **Create a Device**: Add a new virtual device (e.g., Google Pixel 3, Android 10 or 11).
3.  **Enable Root**:
    - Start the virtual device.
    - Sending `adb` commands or using the UI, user root is usually enabled by default or found in **Settings > Developer options**.
    - Genymotion instances usually come with `su` binary in `/system/xbin/su`.
4.  **Install App**:
    - Run `./gradlew assembleDebug` in your terminal.
    - Drag and drop the APK (`app/build/outputs/apk/debug/app-debug.apk`) onto the Genymotion window.
5.  **Run**: Open the app. It should successfully acquire root.

## Option 2: Android Studio AVD + rootAVD
If you prefer the standard Android Studio Emulator, you must patch it with Magisk.

1.  **Create AVD**:
    - Open Device Manager in Android Studio.
    - Create a new Virtual Device.
    - **Important**: Select a system image that is **NOT** "Google Play". Select a **"Google APIs"** image (Target: Android 11.0 to 14.0). Ideally `x86_64`.
2.  **Install Magisk**:
    - Use the [rootAVD](https://github.com/newbit1/rootAVD) script.
    - Clone the repo: `git clone https://github.com/newbit1/rootAVD.git`
    - Run the emulator.
    - Run the script: `./rootAVD.sh List` -> Select your version -> `./rootAVD.sh Install`.
    - The emulator will reboot with Magisk installed.
3.  **Grant Access**: Open the Magisk app inside the emulator to verify root. When your app runs, a popup will appear asking to grant Superuser rights.

## Option 3: Simulation Mode (No Root Required)
If you just want to test the **Network Connection**, **Overlay UI**, and **Backend Logic** without dealing with root:

I have updated the application to support a **Simulation Mode**.
- If the app fails to find Root access, it will ask if you want to run in Simulation Mode.
- **What it does**: Instead of capturing real screen/audio, it generates **Noise/Dummy Frames** and sends them to the server.
- **Benefit**: Allows you to verify that your overlay appears, the network request sends data, and the backend response is processed correctly.

### How to use Simulation Mode:
1. Installation on *any* emulator (even non-rooted).
2. Open App -> Click "Start Service".
3. If Root is missing, a Toast/Popup will appear (or it may auto-fallback, see logs).
4. The Overlay will say "Simulation Mode".
