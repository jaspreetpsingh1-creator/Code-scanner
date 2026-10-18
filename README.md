# Code Scanner

Point your phone at code on a computer screen and scroll. The app reads the code inside the box, adds only lines it hasn't seen, and ignores everything outside the box (tabs, URLs, icons).

## Get the APK
Every push to `main` builds the app. Open **Releases** on this repo from your phone, download `CodeScanner.apk`, and open it (allow "Install unknown apps" for your browser when asked).

## Use it
1. Drag the white box so it covers only the code area of the editor.
2. Tap **Start scanning**.
3. Scroll slowly on the computer. Green outlines show the lines being read.
4. If it says **Lost track**, scroll back up to the last captured line and continue.
5. **Copy** or **Share** when done. **Undo** removes the last batch, **Edit** lets you fix mistakes.

## Tips for accuracy
- Bigger font on the computer (zoom to 130–150%).
- Hold the phone square to the screen, close enough that the code fills the box.
- Dim room lighting reduces glare; a slight angle avoids screen-pattern stripes.
- Indentation is rebuilt from position, snapped to 2 spaces.

## How it works
- CameraX + ML Kit on-device text recognition (no internet needed).
- Each frame's lines are aligned against the end of the captured text; only lines below the overlap are new.
- A new line is committed only after two consecutive frames agree, to filter out blurry frames.
