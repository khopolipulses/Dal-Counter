# Grain Counter (Android)

Counts grains (dal, rice, wheat, lentils, seeds, beans...) in a photo, fully on the phone: no internet, no permissions, ~100 KB APK.

## Get the APK (no Android Studio needed)
1. Put this folder in a new GitHub repository (upload the files, keep the `.github` folder).
2. Open the **Actions** tab, the *Build APK* run starts by itself (or press **Run workflow**).
3. When green, open it, download **grain-counter-apk**, unzip, copy `grain-counter.apk` to the phone, open it.
   Allow "install unknown apps" if asked.

## Use
- **Gallery / Camera**, or Share a photo from your gallery to Grain Counter.
- Best photo: grains spread out, one plain background (any colour that differs from the grains), even light, taken from above.
- Blue ring = certain, **orange** = touching/overlapping group (check these), **green** = added by you.
- Tap an empty spot to add a missed grain, tap a ring to remove one. **Undo** reverts.
- The yellow/green line under the count tells you if the photo is good enough for an exact count.
- **Save** writes a numbered PNG to Pictures/GrainCounter, **Share** sends it anywhere.

## Code
`DalCounter.java` (counting engine) and `Markers.java` (edit list) are plain Java, tested on a desktop JVM (`test/`).
The engine learns the background and grain colour from each photo, so it works for any grain colour/shape.

## Limits
- Grains piled on top of each other cannot be seen; they are flagged orange for you to check.
- Pale grains on a white background (low contrast) are flagged as unreliable.
