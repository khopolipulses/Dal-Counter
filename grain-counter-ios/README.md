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
- Home has **Take Photo** and **Choose from Photos**, your recent counts and tips. The iPhone camera never adds watermarks; photos from other phones that carry a
  "Shot on ..." watermark, date stamp or frame still count correctly, as the watermark is ignored.
- Best photo: grains spread out, one plain background (any colour that differs from the grains), even light, taken from above.
- Printed text, lines and symbols on the sheet or packet (labels, numbers, underlines, arrows, ticks, boxes, rulers)
  are recognised and **not counted**; the line under the count says how many pieces were ignored.
- **Mixed grains** work: wheat, round seeds, lentils, beans... in one photo are each counted once (a big grain is
  not split just because it is bigger than the others).
- **Light reflections** (flash, lamp or window glare on a glossy white table) are ignored.
- Blue ring = counted, **orange** = worth a check, **green** = added by you, blue with a tick = checked by you.
- Tap an empty spot to add a missed grain, tap a ring to remove one. **Undo** reverts. Pinch or double tap to zoom, drag to pan, **Fit** resets.
- The pill under the count says *Looks exact*, *N to check* or *Checked by you*; a tip appears when the photo itself could be better.
- **History** keeps every count on the phone (thumbnail, number, date); open one to zoom, share or delete it.
- **Save** adds the numbered PNG to Photos, **Share** sends it anywhere.

## Getting an exact count
1. Take or choose a photo. The count appears in a second; every ring is one grain.
2. **Blue** rings are counted with confidence. **Orange** rings are the ones worth a look: grains that touch or
   overlap, broken pieces, or anything that looks unlike the other grains in the photo.
3. Tap the orange **"N to check"** pill (or **Review**). The app glides to each orange grain and asks
   *"Is this exactly one grain?"*: **Yes** (it gets a tick) or **Not a grain** (removed). Missed one? Tap the empty
   spot to add it (green). When nothing orange is left, the pill says **Checked by you** and the count is exact.

On the 70 test photos, every extra count the engine still makes outside the pale-rice-on-white cases is flagged
orange, so after Review the final number is right. Grains completely hidden under others cannot be seen by anyone;
spread them out when you need the last grain.

## Design
Apple-style: large titles, frosted glass cards and toolbars over a softly drifting warm background, the photo full screen
under floating glass controls, spring animations (markers pop in, the count counts up, review glides from grain to grain),
haptics, light and dark mode. Native blur materials and SF Symbols; Reduce Motion is respected.

## Privacy and security
- No network code: counting happens entirely on the phone. The camera is asked for only when **Take Photo** is used;
  photos are picked with the system picker (no photo-library permission). Adding to Photos asks for add-only access.
- History is stored with iOS data protection and excluded from iCloud and computer backups; shared pictures are
  temporary files cleared at the next launch. `PrivacyInfo.xcprivacy` declares no tracking and no collected data.

## Testing (runs in CI on every push)
- `swift test`: marker list, robustness, reflections, mixed grains, print removal and a 300-image fuzz.
- Java/Swift parity: the fuzz results must be bit-identical to the Android engine's.
- UI tests on the simulator (`UITests/`): home, counting a test photo, guided review, History, dark mode with mixed
  grains and glare, rotation, and Apple's accessibility audit on each screen. Screenshots are uploaded as
  `ios-simulator-screenshots`. (A Debug-only launch argument opens the test photo; it is not in release builds.)

## Code
- `Core/` - `DalCounter.swift` (counting engine) and `Markers.swift` (edit list). Plain Swift, no UIKit:
  `swift test` runs their tests on macOS or Linux.
- `App/` - UIKit screens (`HomeViewController`, `ResultViewController` with review, `HistoryViewController`), the zoomable
  photo view (`CountView`), `Theme` (glass, buttons, background, toasts), `HistoryStore`, Info.plist and icon.
- `project.yml` - XcodeGen spec for the app (the `.xcodeproj` is generated, not committed).

Limits are the same as on Android (see `../grain-counter/README.md`). If you change the engine, change `grain-counter/.../DalCounter.java` the same way so the two apps stay in step.
