# Tasks - Fix Phone Detection Internal Error

- [x] Refactor detection managers to return Tasks
    - [x] Update `FaceDetectionManager.kt`
    - [x] Update `PhoneDetectionManager.kt`
- [x] Update `MainActivity.kt` to coordinate tasks
    - [x] Implement task synchronization in `ImageAnalysis.Analyzer`
    - [x] Clean up redundant camera and permission logic
- [ ] Verify fix
    - [ ] Check logs for `MlKitException`
    - [ ] Confirm face and phone detection still work
