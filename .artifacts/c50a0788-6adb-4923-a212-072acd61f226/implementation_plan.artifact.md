# Implementation Plan - YOLO Detection Root Cause Investigation

The goal is to identify why "cell phone" scores are extremely low despite the model being functional (high "person" scores). We will systematically test normalization ranges, channel ordering, and coordinate scaling.

## User Review Required

> [!IMPORTANT]
> **Dual-Inference Diagnosis**: I will modify `detect()` to optionally run twice (or log comparative stats) to test different normalization scales (`[0, 1]` vs `[0, 255]`). This is the most likely reason for extremely low scores if the model graph doesn't include a division-by-255 layer.

> [!CAUTION]
> **Front Camera Mirroring**: Front camera images are mirrored by default. I will add a horizontal flip to the preprocessing to ensure the model sees a "natural" view, which can sometimes improve detection of specific devices.

## Proposed Changes

### [Detection Engine]

#### [MODIFY] [YOLODetector.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/YOLODetector.kt)
- **Normalization Test**: Add a flag to toggle between `/ 255.0f` and raw `0..255` floats in the input buffer.
- **BGR Swap**: Add logic to test BGR order (Blue-Green-Red) instead of RGB.
- **Coordinate Magnitude Check**: Log raw values of `output[0][0..3]` for the candidate with the highest class score to determine if they are normalized or pixel-space.
- **Preprocessing Flip**: Add a `Matrix` flip (horizontal) during the letterboxing stage to un-mirror the front camera feed.

## Verification Plan

### Manual Verification
- **Run the app** and observe Logcat for `YOLODetector`.
- **Compare Scores**: Report the `CELL PHONE MAX SCORE` for the different normalization/channel settings.
- **Check Box Scales**: Verify if the logged raw coordinates are small ($< 1$) or large ($> 1$).
