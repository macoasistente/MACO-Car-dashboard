# Sophie's Dashboard (MACO Car Dashboard)

A phone and car-screen dashboard for Sophie, a 2018 Kia Soul+. It talks to a Bluetooth OBD2 adapter (Vgate iCar Pro BLE) and shows live data, trouble codes in plain English, oil and maintenance tracking, recalls and the parking spot. The look is a dark "liquid glass" style.

## Open it
- **Android:** open the GitHub Pages link in Chrome, then use "Add to Home screen".
- **iPhone:** open the link in the free **Bluefy** browser (Safari has no Bluetooth access).
- Tap **Connect** and pick the adapter (shows as `IOS-Vlink` or `Android-Vlink`). Don't pair it in the phone's Bluetooth settings first.

## Files
- `index.html` is the whole app (one file, no build step).
- `manifest.webmanifest`, `sw.js`, `icons/` make it installable and let it open without signal.
- `tools/relight-photo.py` relights a car photo to sit in the dark UI.

## Plan
1. Web app (this repo, GitHub Pages).
2. Native Android wrapper and an Android Auto dashboard screen.
3. CarPlay screen, pending Apple's approval.

Works with any 1996+ car: add more cars in Setup.
