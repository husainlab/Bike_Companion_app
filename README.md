# Bluetooth Companion App

A dual-purpose Android application designed to mirror phone status and control media/calls on a secondary device via Bluetooth. It is ideal for use cases where you want a secondary display (like a tablet or a car head unit) to act as a remote control and notification dashboard for your primary phone.

## Features

- **Dual Mode Architecture**:
  - **Sender Mode**: Installed on your primary phone. It monitors notifications, media, and calls.
  - **Receiver Mode**: Installed on the display device. It shows a persistent, draggable overlay with all mirrored data.
- **Notification Mirroring**: See incoming notifications from any app in real-time on the receiver device.
- **Media Control & Sync**:
  - View current song title, artist, and playback progress.
  - Remote control playback (Play/Pause, Skip, Volume Up/Down) from the receiver.
- **Call Management**:
  - Incoming call alerts with caller name (Contact sync).
  - Answer or Decline calls directly from the overlay widget.
- **System Monitoring**: View the sender phone's battery percentage on the receiver.
- **Persistent Overlay**: A modern, Jetpack Compose-based floating widget that stays on top of other apps.
- **Auto-Reconnect**: Automatically attempts to restore the Bluetooth connection if lost.

## Permissions Required

To function correctly, the app requires several sensitive permissions:
- **Bluetooth**: For device discovery and communication.
- **Notification Access**: Required for the Sender to read and forward notifications.
- **Display Over Other Apps**: Required for the Receiver to show the floating overlay.
- **Phone & Contacts**: To detect incoming calls and resolve phone numbers to contact names.
- **Answer Phone Calls**: To allow remote answering/declining of calls.

## Getting Started

### Prerequisites
- Two Android devices (Android 8.0+ recommended).
- Devices must be paired via Android System Bluetooth settings before using the app.

### Installation
1. Clone the repository.
2. Build the APK using Gradle:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. Install the APK on both devices.

### Usage
1. **On the Primary Phone (Sender)**:
   - Open the app and switch to **Sender Mode**.
   - Grant **Notification Access** when prompted.
   - Tap **Start Server**.
2. **On the Display Device (Receiver)**:
   - Open the app and ensure it is in **Receiver Mode**.
   - Tap **Connect to Sender** and select your primary phone from the paired devices list.
   - Once connected, tap **Start Overlay Widget** (grant overlay permissions if needed).

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Communication**: Bluetooth RFCOMM (SPP Profile)
- **Services**: `NotificationListenerService`, `Foreground Service`

## License
[MIT License](LICENSE)
