# Implementation Plan - Fix Phone Detection Internal Error

The application is experiencing a `com.google.mlkit.common.MlKitException: Internal error` during phone detection. This is likely caused by the `ImageProxy` being closed before the asynchronous ML Kit processing tasks are completed.

## Proposed Changes

### 1. Refactor Detection Managers
Modify both `FaceDetectionManager` and `PhoneDetectionManager` to return the `Task` object from their `process` methods. This allows the caller (`MainActivity`) to know when the processing is finished.

- [MODIFY] [FaceDetectionManager.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/FaceDetectionManager.kt)
- [MODIFY] [PhoneDetectionManager.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/PhoneDetectionManager.kt)

### 2. Update MainActivity to Coordinate Tasks
Modify the `ImageAnalysis.Analyzer` in `MainActivity.kt` to wait for both `FaceDetection` and `PhoneDetection` tasks to complete before calling `imageProxy.close()`.

- [MODIFY] [MainActivity.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/MainActivity.kt)

### 3. Clean up MainActivity
- Remove redundant camera start logic.
- Remove redundant permission check blocks.

## Verification Plan

### Automated Tests
- I will run the application and verify that the "Phone detection failed" error no longer appears in Logcat.
- I will verify that face detection and phone detection still function correctly (logs show results).

### Manual Verification
- Deploy the app to a device/emulator.
- Check Logcat for any `MlKitException`.
- Verify that the "⚠ ADDITIONAL VIEWER DETECTED" warning still triggers when multiple faces are present.
