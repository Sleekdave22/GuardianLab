# Implementation Plan - Instant Response and UI Cleanup

The goal is to eliminate detection latency for immediate protection and clean up the UI by removing the face count display.

## Proposed Changes

### [UI / Layout]

#### [MODIFY] [MainActivity.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/MainActivity.kt)
- **Hide Face Count**:
    - Remove the `faceCountText` initialization and its addition to the layout.
    - Remove `overlayManager.updateFaceCount(count)` from the face detection callback.
- **Optimize Detection Loop**:
    - Ensure YOLO detection happens first or in parallel (it's currently sequential but fast).
    - Trigger `shieldController.showProtection()` immediately after the `currentFrameThreat` check, even before starting the next frame analysis.
- **Shorten Delay**: Reduce the `hideProtectionWithDelay` to a shorter value if appropriate, but keeping it at 5s for the YOLO "sticky" state to ensure once triggered, it stays triggered.

### [Detection Engine]

#### [MODIFY] [OverlayManager.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/OverlayManager.kt)
- Remove `updateFaceCount` method and the reference to `faceCountText`.

## Verification Plan

### Manual Verification
- **Speed Test**: Point a phone at the camera quickly. The shield should appear almost instantly.
- **UI Test**: Verify that the "Faces: X" text is no longer visible on the screen.
- **Consistency**: Ensure that multiple faces still trigger the shield, but without the counter being visible.
