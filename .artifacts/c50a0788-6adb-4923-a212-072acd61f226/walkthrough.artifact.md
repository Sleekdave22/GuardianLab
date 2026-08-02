# Walkthrough - Phone Detection Internal Error Fix

I have fixed the `MlKitException: Internal error` by ensuring that the camera image buffer is only closed after all ML Kit processing tasks (Face Detection and Phone Detection) are completed.

## Changes Made

### Detection Managers
Updated both `FaceDetectionManager` and `PhoneDetectionManager` to return a `Task` object from their `process` methods. This allows for asynchronous coordination.

- [FaceDetectionManager.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/FaceDetectionManager.kt)
- [PhoneDetectionManager.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/PhoneDetectionManager.kt)

### Main Activity Synchronization
Updated `MainActivity.kt` to use `Tasks.whenAllComplete` to wait for both detection tasks before closing the `ImageProxy`. This prevents the "Internal error" caused by accessing a closed image.

- [MainActivity.kt](file:///Users/mac/AndroidStudioProjects/GuardianLab/app/src/main/java/com/guardianlab/app/MainActivity.kt)

### Code Cleanup
Removed redundant camera initialization and permission check blocks in `MainActivity.kt`.

## Verification Results

### Automated Tests
- Ran `analyze_file` on modified files; no compilation errors were found.
- The logic now explicitly handles the lifecycle of the `ImageProxy` relative to the asynchronous ML Kit tasks.

### Manual Verification
- You should now be able to run the app without seeing the "Phone detection failed" error in Logcat.
- Face detection and phone detection logs should appear correctly as processing finishes.
