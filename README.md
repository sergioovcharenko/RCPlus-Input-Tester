# RC Plus Input Tester 0.3

Android diagnostic app for DJI RC Plus 2 Enterprise, built with DJI Mobile SDK V5. Read-only monitoring of supported controls. No flight commands.

## Build

Add repository secret `DJI_APP_KEY` at Settings → Secrets and variables → Actions. The App Key must match the Android package `com.serhii.rcplustester` registered with DJI. GitHub Actions builds `app-debug.apk` on `main` pushes or manual runs. Do not commit or publish the key.

The SDK may not expose every RC Plus 2 physical button. Test with the aircraft powered off. This application displays Android and SDK events, not RC radio channels.
