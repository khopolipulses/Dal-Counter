# Grain Counter (Android)

Counts grains (dal, rice, wheat, lentils, seeds, beans...) in a photo, fully on the phone: no internet, ~100 KB APK.
The only permission is the camera (asked the first time you press **Camera**).

## Get the APK (no Android Studio needed)
1. Put this folder in a new GitHub repository (upload the files, keep the `.github` folder).
2. Open the **Actions** tab, the *Build APK* run starts by itself (or press **Run workflow**).
3. When green, open it, download **grain-counter-apk**, unzip, copy `grain-counter.apk` to the phone, open it.
   Allow "install unknown apps" if asked.

## Use
- Home has **Take Photo** and **Choose from Photos**, your recent counts and tips; or Share a photo from your gallery to Grain Counter.
- **Camera** is the app's own camera, not the phone's camera app, so photos never get a "Shot on ..." watermark,
  date stamp or frame. The photo stays private to the app (deleted after 3 days); **Save** puts the counted picture
  in Pictures/GrainCounter when you want it.
- Best photo: grains spread out, one plain background (any colour that differs from the grains), even light, taken from above.
- Printed text, lines and symbols on the sheet or packet (labels, numbers, underlines, arrows, ticks, boxes, rulers)
  are recognised and **not counted**; the line under the count says how many pieces were ignored.
  Phone camera watermarks in gallery photos ("Shot on Redmi ...", "AI QUAD CAMERA", phone model + lens frame,
  date stamps, small brand logos) are ignored the same way.
- **Mixed grains** work: wheat, round seeds, lentils, beans... in one photo are each counted once (a big grain is
  not split just because it is bigger than the others).
- **Light reflections** (flash, lamp or window glare on a glossy white table) are ignored.
- Blue ring = counted, **orange** = worth a check, **green** = added by you, blue with a tick = checked by you.
- Tap an empty spot to add a missed grain, tap a ring to remove one. **Undo** reverts. Pinch or double tap to zoom.
- The pill under the count says *Looks exact*, *N to check* or *Checked by you*; a tip appears when the photo itself could be better.
- **History** keeps every count on the phone (thumbnail, number, date); open one to zoom, share or delete it.
- **Save** writes a numbered PNG to Pictures/GrainCounter. **Share** sends it anywhere without adding it to the gallery.

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
haptics, light and dark mode. Glass over the photo is a live blur drawn by the app, so it works on every Android version;
no libraries are used.

## Privacy and security
- No internet permission: the app cannot send anything anywhere. Counting happens entirely on the phone.
- Only the camera permission is asked for (first time **Camera** is used). Photos are picked with the system picker.
- Counts, pictures and temporary files live in the app's private storage and are excluded from cloud backup and from
  transfer to a new phone.
- Photos shared into the app are opened only through content Uris; `file://` Uris (which could point at the app's
  own private files) are refused, and images with unreadable or absurd dimensions are rejected before decoding.
- Sharing uses a private, read-only provider that serves exactly one temporary file per permission grant; file names
  are validated and paths canonicalised, so crafted Uris cannot reach other files. The camera screen and the
  provider are not exported.

## Testing (runs in CI on every push)
- **Android Lint** must report no errors.
- **Engine tests**: `Robust` (degenerate images), `MarkersTest`, and `Fuzz` - 300 pathological images (1-pixel strips,
  noise, flat colours, checkerboards, discs with strokes and glare) that must never crash or put a marker outside the
  image, plus thousands of random add/remove/confirm/undo edits where undo must restore the exact previous state.
- **Accuracy gate**: the 70 benchmark photos of `gen_bench.py` must stay at least 58 exact and 68 within +-2.
- **Java/Swift parity**: the iOS engine must give bit-identical results to this one on the fuzz images.
- **Device test** (`test/device-test.sh`, Android 15 emulator): shares a photo in, reviews it, checks History,
  takes a picture with the camera, dark mode, rotation, hostile intents (`file://` into private files, junk
  intents, reading the private provider, starting the camera screen from outside), 4000 random monkey events and a
  process kill; fails on any crash or ANR. Screenshots are uploaded as `android-device-screenshots`.

## Code
Screens: `HomeScreen`, `ResultScreen` (count + review), `HistoryScreen`, `PictureScreen`, hosted by `MainActivity`
(navigation stack with iOS-style push/pop); `Ui` and `Glass` hold the design system; `History` stores past counts.
`DalCounter.java` (counting engine) and `Markers.java` (edit list) are plain Java, tested on a desktop JVM (`test/`).
The engine learns the background and grain colour from each photo, so it works for any grain colour/shape.

## Limits
- Grains piled on top of each other cannot be seen; they are flagged orange for you to check.
- Pale grains on a white background (low contrast) are flagged as unreliable.
- Reflections are ignored on light backgrounds only; on a dark tray, avoid flash glare (it looks like a white grain).
- A grain lying right under watermark letters may be missed or split; use the in-app **Camera** to avoid watermarks.
- Large **solid** printed shapes (a filled logo or a big bold headline) can still be counted as grains; a grain
  lying on top of a printed line is counted correctly. Plain paper is still the safest background.
