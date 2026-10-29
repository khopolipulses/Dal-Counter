# Grain Counter (iOS)

The iPhone / iPad version of the Android app in `../grain-counter`. Same counting engine, ported line by line to Swift,
so both apps give the same count for the same photo. Fully on the phone: no internet, no account.

## Get the app (no Mac needed)
1. Push to GitHub. The **Build iOS app** workflow runs on a macOS runner (or open **Actions** and press **Run workflow**).
2. When green, open the run, download **grain-counter-ipa**, unzip it to get `grain-counter.ipa`.
3. The IPA is **unsigned**: iOS only installs apps signed for your device. Sign and install it with a sideloading tool
   that uses your own Apple ID, such as [AltStore](https://altstore.io) or [Sideloadly](https://sideloadly.io).
   With a free Apple ID the app must be re-signed every 7 days; a paid Apple Developer account lasts a year and also
   allows TestFlight / App Store.

With a Mac you can also run it directly: `brew install xcodegen && xcodegen generate`, open `GrainCounter.xcodeproj`,
choose your team under *Signing & Capabilities*, plug in the phone and press Run.

## Use
- **Photos** or **Camera**.
- Best photo: grains spread out, one plain background (any colour that differs from the grains), even light, taken from above.
- Blue ring = certain, **orange** = touching/overlapping group (check these), **green** = added by you.
- Tap an empty spot to add a missed grain, tap a ring to remove one. **Undo** reverts. Pinch to zoom, drag to pan, **Fit** resets.
- The yellow/green line under the count tells you if the photo is good enough for an exact count.
- **Save** adds the numbered PNG to Photos, **Share** sends it anywhere.

## Code
- `Core/` - `DalCounter.swift` (counting engine) and `Markers.swift` (edit list). Plain Swift, no UIKit:
  `swift test` runs their tests on macOS or Linux.
- `App/` - UIKit screen (`MainViewController`), the zoomable photo view (`CountView`), Info.plist and icon.
- `project.yml` - XcodeGen spec for the app (the `.xcodeproj` is generated, not committed).

If you change the engine, change `grain-counter/.../DalCounter.java` the same way so the two apps stay in step.
