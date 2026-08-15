# Tasks - YOLO Detection Root Cause Investigation

- [ ] Update `YOLODetector.kt` with diagnostic logic
    - [ ] Add horizontal flip to letterboxing (un-mirror)
    - [ ] Implement normalization range test (`[0, 1]` vs `[0, 255]`)
    - [ ] Add BGR vs RGB channel order test
    - [ ] Add raw box coordinate logging
    - [ ] Log comparative stats for different normalization modes
- [ ] Verify fix
    - [ ] Run `analyze_file`
    - [ ] Observe Logcat for diagnostic results
