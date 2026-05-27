# Mukistaja

An Android app for uploading images to the [Paulig Muki](https://www.pauliggroup.com/news/limited-edition-of-the-new-smart-coffee-cup-paulig-muki-now-available) e-ink coffee mug.

All the real work — reverse engineering the Muki's Bluetooth protocol, figuring out the image format, and writing the original scripts — was done by [jku](https://github.com/jku) in the [mukinator](https://github.com/jku/mukinator) project. Mukistaja is just an Android front-end built on top of that work.

## Background

The Paulig Muki is a thermoelectric coffee mug with a 176×264 pixel 1-bit e-ink display on the side. The official app required a user account on Paulig's servers to function. When sales of the product ended, Paulig shut the servers down, breaking every device sold — despite the fact that the mug communicates over Bluetooth LE with no authentication whatsoever and never needed a server in the first place.

The mukinator project reverse engineered the protocol and gave the Muki a second life. Mukistaja brings that to Android.

## Requirements

- Android 8.0 (API 26) or newer
- Bluetooth LE
- A Paulig Muki mug
- The mug needs to be warm enough to power on — pour some coffee first

## Installation

Download `mukistaja.apk` from the [Releases](../../releases) page. You will need to enable "Install unknown apps" for whatever app you use to open it (browser, file manager, etc.), as Mukistaja is not distributed through the Play Store.

## Usage

1. **Pick Image** — choose a photo from your gallery
2. **Frame it** — pan and pinch to zoom until the crop frame shows what you want. The preview updates in real time showing the actual black-and-white dithered result, so what you see is what the mug gets
3. **Rotate** — use the Rotate 90° button if the image orientation needs adjusting
4. **Scan Devices** — scans for nearby Bluetooth LE devices for 10 seconds and lists them, sorted by signal strength. Your Muki should appear near the top if it's powered on
5. **Tap your Muki** in the list to select it
6. **Send to Muki** — connects and uploads

## How it works

The Muki screen is 176×264 pixels, 1-bit (pure black and white). Mukistaja ports the image processing from mukinator's `muki_img.py`:

- The selected crop is resized to 176×264
- Converted to greyscale and dithered to 1-bit using Floyd-Steinberg dithering
- Rotated 90° clockwise (the screen is physically mounted rotated)
- Packed into 5808 bytes and sent over BLE in 291 chunks of 20 bytes, preceded by a start byte (`0x74`) and followed by an end byte (`0x64`), to characteristic UUID `06640002-9087-04a8-658f-ce44cb96b4a1`

## Building from source

Requirements: Android Studio, JDK 17, Android SDK 34.

```bash
git clone https://github.com/k-vuohi/mukistaja.git
cd mukistaja-android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/mukistaja.apk
```
