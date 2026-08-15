# Tasks - Fix Camera Preview Dimming and Layout

- [x] Fix `MainActivity.kt` UI layout
    - [x] Update `PreviewView` scale type to `FILL_CENTER`
    - [x] Set explicit `LayoutParams` for `faceCountText` and `warningText` (`WRAP_CONTENT` height)
    - [x] Initialize `warningText` as `GONE`
- [x] Update `OverlayManager.kt` visibility logic
    - [x] Manage `VISIBLE`/`GONE` states for text overlays
- [x] Verify fix
    - [x] Preview brightness matches system camera
    - [x] Overlays appear correctly without dimming the whole screen
