package com.guardianlab.app

import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@androidx.camera.core.ExperimentalGetImage
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var warningText: TextView
    private lateinit var shieldView: android.view.View
    private lateinit var sensitiveContentText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var yoloDetector: YOLODetector
    private lateinit var blurPanel: android.view.View
    private lateinit var shieldController: ShieldController
    private lateinit var cameraManager: CameraManager
    private lateinit var faceDetectionManager: FaceDetectionManager
    private val yoloDetectionHistory = ArrayDeque<Boolean>()
    private var isYoloThreatConfirmed = false
    private var lastStrongYoloElapsedMs: Long? = null
    private val lightingAnalyzer = LightingAnalyzer()
    private var lastYoloStrongConfidence: Float? = null

    private companion object {
        // LIGHTING-DEPENDENT YOLO PARAMETERS
        //
        // DARK 0.50: provisionally validated dark-profile threshold.
        // NORMAL 0.40: provisional normal-light threshold.
        // Color cast is tracked separately but does not yet alter threshold.
        const val YOLO_DARK_STRONG_CONFIDENCE = 0.50f
        const val YOLO_NORMAL_NEUTRAL_STRONG_CONFIDENCE = 0.40f
        const val YOLO_NORMAL_COLORED_STRONG_CONFIDENCE = 0.50f

        const val YOLO_MIN_BOX_WIDTH = 50f
        const val YOLO_MIN_BOX_HEIGHT = 50f

        const val YOLO_CONFIRMATION_WINDOW = 5
        const val YOLO_CONFIRMATION_REQUIRED = 3

        const val YOLO_HOLD_MS = 7_500L
    }
    private lateinit var overlayManager: OverlayManager
    private lateinit var guardianEngine: GuardianEngine
    private lateinit var guardian: Guardian
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraManager.startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("GuardianFlow", "MAIN ACTIVITY STARTED")

        // MobileClipSequential7Probe.run(this) // D1-L8L completed; disabled

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        sensitiveContentText = TextView(this).apply {
            textSize = 26f
            setTextColor(android.graphics.Color.BLACK)
            setBackgroundColor(android.graphics.Color.WHITE)
            text = "Account Balance\n₦2,450,000"
            setPadding(40, 40, 40, 40)
        }

        warningText = TextView(this).apply {
            textSize = 28f
            setTextColor(android.graphics.Color.RED)
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            text = ""
            setPadding(20, 120, 20, 40)
            visibility = android.view.View.GONE
        }
        shieldView = android.view.View(this).apply {
            setBackgroundColor(
                android.graphics.Color.argb(245, 0, 0, 0)
            )
            visibility = android.view.View.GONE
        }
        blurPanel = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.argb(0, 0, 0, 0))
            visibility = android.view.View.GONE
        }

        val layout = android.widget.FrameLayout(this)
        layout.addView(previewView)
        
        val warningParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        layout.addView(warningText, warningParams)

        val sensitiveContentParams =
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        layout.addView(sensitiveContentText, sensitiveContentParams)
        val blurParams = android.widget.FrameLayout.LayoutParams(
            700,
            300
        ).apply {
            gravity = android.view.Gravity.CENTER
        }

        layout.addView(shieldView)
        layout.addView(blurPanel, blurParams)

        val diagnosticButton = android.widget.Button(this).apply {
            text = "START D1-L8C"
            setOnClickListener {
                if (yoloDetector.isDiagnosticActive()) {
                    val summary = yoloDetector.stopDiagnostic()
                    text = "START D1-L8C"
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "D1-L8C Stopped: ${summary?.savedPngCount ?: 0} PNGs saved",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    yoloDetector.startDiagnostic(filesDir)
                    text = "STOP D1-L8C"
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "D1-L8C Started",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        val diagnosticButtonParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = 50
        }
        layout.addView(diagnosticButton, diagnosticButtonParams)
        diagnosticButton.bringToFront()

        warningText.bringToFront()
        setContentView(layout)
        shieldController = ShieldController(
            shieldView,
            blurPanel,
            warningText,
            layout
        )
        guardian = Guardian(shieldController)
        guardian.protect(sensitiveContentText)
        overlayManager = OverlayManager(
            warningText
        )
        guardianEngine = GuardianEngine()
        faceDetectionManager = FaceDetectionManager { count ->

            runOnUiThread {
                Log.d("GuardianFlow", "Face count received: $count")

                val shouldProtect =
                    guardianEngine.shouldActivateProtection(count) || isYoloThreatConfirmed

                if (shouldProtect) {
                    val message =
                        if (isYoloThreatConfirmed) {
                            "⚠ CAMERA DEVICE DETECTED"
                        } else {
                            "⚠ ADDITIONAL VIEWER DETECTED"
                        }

                    overlayManager.showWarning(message)
                    shieldController.showProtection(message)
                } else {
                    overlayManager.clearWarning()
                    shieldController.hideProtectionWithDelay()
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        val modelLoader = ModelLoader(this)
        yoloDetector = YOLODetector(modelLoader)

        cameraManager = CameraManager(
            lifecycleOwner = this,
            previewView = previewView,
            cameraExecutor = cameraExecutor,
            imageAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
                try {
                    val mediaImage = imageProxy.image

                    if (mediaImage == null) {
                        imageProxy.close()
                        return@Analyzer
                    }

                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )

                    val faceTask = faceDetectionManager.process(image)
                    
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val rawBitmap = imageProxy.toBitmap()
                    
                    val matrix = Matrix().apply {
                        postRotate(rotation.toFloat())
                    }
                    
                    val bitmap = Bitmap.createBitmap(
                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                    )

                    Log.d("YOLODetector", "ROTATION DEGREES: $rotation")
                    var totalR = 0L
                    var totalG = 0L
                    var totalB = 0L
                    var samples = 0

                    val step = 20

                    for (y in 0 until bitmap.height step step) {
                        for (x in 0 until bitmap.width step step) {
                            val pixel = bitmap.getPixel(x, y)

                            totalR += android.graphics.Color.red(pixel)
                            totalG += android.graphics.Color.green(pixel)
                            totalB += android.graphics.Color.blue(pixel)
                            samples++
                        }
                    }

                    val avgR = totalR / samples
                    val avgG = totalG / samples
                    val avgB = totalB / samples
                    val avgBrightness = (0.299 * avgR) + (0.587 * avgG) + (0.114 * avgB)

                    val lightingState = lightingAnalyzer.update(
                        avgR = avgR.toInt(),
                        avgG = avgG.toInt(),
                        avgB = avgB.toInt(),
                        brightness = avgBrightness
                    )

                    Log.d(
                        "GL_LIGHTING",
                        "profile=${lightingState.profileKey} | " +
                            "rawBrightness=${"%.1f".format(java.util.Locale.US, lightingState.rawBrightness)} | " +
                            "smoothBrightness=${"%.1f".format(java.util.Locale.US, lightingState.smoothedBrightness)} | " +
                            "normRGB=(" +
                            "%.3f".format(java.util.Locale.US, lightingState.normalizedR) + "," +
                            "%.3f".format(java.util.Locale.US, lightingState.normalizedG) + "," +
                            "%.3f".format(java.util.Locale.US, lightingState.normalizedB) + ")"
                    )

                    Log.d(
                        "YOLODetector",
                        "YOLO INPUT: avgRGB=($avgR,$avgG,$avgB), brightness=${"%.1f".format(avgBrightness)}"
                    )
                    val frameResult = yoloDetector.detectFrame(bitmap)
                    val yoloDetections = frameResult.detections
                    val proposals = frameResult.proposals

                    // ============================================================
                    // D1-L7A — BEST YOLO PROPOSAL CROP VALIDATION
                    // Diagnostic only. Does NOT affect Guardian protection logic.
                    // ============================================================

                    val bestProposal = proposals.maxByOrNull { it.confidence }

                    if (bestProposal != null) {

                        val left = bestProposal.x1.toInt()
                            .coerceIn(0, bitmap.width - 1)

                        val top = bestProposal.y1.toInt()
                            .coerceIn(0, bitmap.height - 1)

                        val right = bestProposal.x2.toInt()
                            .coerceIn(left + 1, bitmap.width)

                        val bottom = bestProposal.y2.toInt()
                            .coerceIn(top + 1, bitmap.height)

                        val cropWidth = right - left
                        val cropHeight = bottom - top

                        // ============================================================
                        // D1-L7B — MATERIALIZE BEST PROPOSAL CROP
                        // ============================================================

                        val proposalCrop = Bitmap.createBitmap(
                            bitmap,
                            left,
                            top,
                            cropWidth,
                            cropHeight
                        )

                        var cropPixelCount = 0
                        var cropR = 0L
                        var cropG = 0L
                        var cropB = 0L

                        val sampleStep = maxOf(
                            1,
                            minOf(proposalCrop.width, proposalCrop.height) / 32
                        )

                        for (y in 0 until proposalCrop.height step sampleStep) {
                            for (x in 0 until proposalCrop.width step sampleStep) {

                                val pixel = proposalCrop.getPixel(x, y)

                                cropR += (pixel shr 16) and 0xFF
                                cropG += (pixel shr 8) and 0xFF
                                cropB += pixel and 0xFF

                                cropPixelCount++
                            }
                        }

                        val cropAvgR =
                            if (cropPixelCount > 0) cropR.toDouble() / cropPixelCount else 0.0

                        val cropAvgG =
                            if (cropPixelCount > 0) cropG.toDouble() / cropPixelCount else 0.0

                        val cropAvgB =
                            if (cropPixelCount > 0) cropB.toDouble() / cropPixelCount else 0.0

                        Log.d(
                            "GL_MOBILECLIP_L7",
                            "D1-L7A | " +
                                "conf=${"%.6f".format(java.util.Locale.US, bestProposal.confidence)} | " +
                                "box=[$left,$top,$right,$bottom] | " +
                                "crop=${cropWidth}x${cropHeight} | " +
                                "frame=${bitmap.width}x${bitmap.height}"
                        )

                        Log.d(
                            "GL_MOBILECLIP_L7",
                            "D1-L7B | crop materialized PASS | " +
                                "size=${proposalCrop.width}x${proposalCrop.height} | " +
                                "avgRGB=(" +
                                "%.1f".format(java.util.Locale.US, cropAvgR) + "," +
                                "%.1f".format(java.util.Locale.US, cropAvgG) + "," +
                                "%.1f".format(java.util.Locale.US, cropAvgB) + ")"
                        )

                        proposalCrop.recycle()
                    }

                    Log.d(
                        "GL_PROPOSALS",
                        "D1-L3 | proposals=${proposals.size} | " +
                            proposals.mapIndexed { index, detection ->
                                "#${index + 1}=" +
                                    "%.6f".format(
                                        java.util.Locale.US,
                                        detection.confidence
                                    )
                            }.joinToString(" ")
                    )

                    Log.d(
                        "YOLODetector",
                        "YOLO detections: ${yoloDetections.size}"
                    )
                    yoloDetections.forEachIndexed { index, detection ->
                        Log.d(
                            "YOLODetector",
                            "Detection[$index]: class=${detection.className}, " +
                                    "confidence=${detection.confidence}, " +
                                    "box=(${detection.x1}, ${detection.y1})-(${detection.x2}, ${detection.y2})"
                        )
                    }

                    val activeYoloStrongConfidence =
                        when (lightingState.intensity) {

                            LightIntensity.NORMAL -> {
                                when (lightingState.colorCast) {
                                    LightColorCast.NEUTRAL ->
                                        YOLO_NORMAL_NEUTRAL_STRONG_CONFIDENCE

                                    LightColorCast.BLUE,
                                    LightColorCast.RED,
                                    LightColorCast.GREEN,
                                    LightColorCast.UNKNOWN ->
                                        YOLO_NORMAL_COLORED_STRONG_CONFIDENCE
                                }
                            }

                            LightIntensity.DARK ->
                                YOLO_DARK_STRONG_CONFIDENCE

                            LightIntensity.UNKNOWN ->
                                YOLO_DARK_STRONG_CONFIDENCE
                        }

                    /*
                     * Do not let confirmation-history frames collected under
                     * one confidence threshold carry into another threshold.
                     *
                     * This also handles color-state changes such as:
                     * NORMAL_BLUE (0.50) -> NORMAL_NEUTRAL (0.40).
                     *
                     * Existing confirmed protection is NOT cleared here.
                     */
                    if (lastYoloStrongConfidence != activeYoloStrongConfidence) {

                        if (!isYoloThreatConfirmed) {
                            yoloDetectionHistory.clear()
                        }

                        Log.d(
                            "GL_LIGHTING",
                            "YOLO threshold switch | " +
                                "profile=${lightingState.profileKey} | " +
                                "from=$lastYoloStrongConfidence | " +
                                "to=${"%.2f".format(
                                    java.util.Locale.US,
                                    activeYoloStrongConfidence
                                )}"
                        )

                        lastYoloStrongConfidence =
                            activeYoloStrongConfidence
                    }

                    val hasStrongPhoneDetection = yoloDetections.any {
                        it.classId == 0 &&
                            it.className == "phone" &&
                            it.confidence >= activeYoloStrongConfidence &&
                            (it.x2 - it.x1) >= YOLO_MIN_BOX_WIDTH &&
                            (it.y2 - it.y1) >= YOLO_MIN_BOX_HEIGHT
                    }

                    val nowElapsedMs = android.os.SystemClock.elapsedRealtime()

                    if (!isYoloThreatConfirmed) {

                        // ADAPTIVE LIGHTING PROFILE:
                        // Initial activation requires strong, substantial phone
                        // evidence in at least 3 of the last 5 analyzed frames.
                        yoloDetectionHistory.addLast(hasStrongPhoneDetection)

                        if (yoloDetectionHistory.size > YOLO_CONFIRMATION_WINDOW) {
                            yoloDetectionHistory.removeFirst()
                        }

                        val strongFrames = yoloDetectionHistory.count { it }

                        if (strongFrames >= YOLO_CONFIRMATION_REQUIRED) {
                            isYoloThreatConfirmed = true
                            lastStrongYoloElapsedMs = nowElapsedMs

                            Log.d(
                                "YOLODetector",
                                "YOLO THREAT CONFIRMED | " +
                                    "history=$yoloDetectionHistory | " +
                                    "strongFrames=$strongFrames"
                            )

                            runOnUiThread {
                                overlayManager.showWarning("⚠ CAMERA DEVICE DETECTED")
                                shieldController.showProtection("⚠ CAMERA DEVICE DETECTED")
                            }
                        } else {
                            Log.d(
                                "YOLODetector",
                                "YOLO waiting for confirmation | " +
                                    "history=$yoloDetectionHistory | " +
                                    "strongFrames=$strongFrames"
                            )
                        }

                    } else {

                        // Once protection has been established, only another
                        // strong substantial phone detection refreshes the hold.
                        if (hasStrongPhoneDetection) {
                            lastStrongYoloElapsedMs = nowElapsedMs

                            Log.d(
                                "YOLODetector",
                                "YOLO CONFIRMED — strong substantial phone detection; hold refreshed"
                            )

                            runOnUiThread {
                                overlayManager.showWarning("⚠ CAMERA DEVICE DETECTED")
                                shieldController.showProtection("⚠ CAMERA DEVICE DETECTED")
                            }

                        } else {

                            val lastStrongMs =
                                lastStrongYoloElapsedMs ?: nowElapsedMs

                            val elapsedSinceStrong =
                                nowElapsedMs - lastStrongMs

                            val remainingMs =
                                (YOLO_HOLD_MS - elapsedSinceStrong).coerceAtLeast(0L)

                            Log.d(
                                "YOLODetector",
                                "YOLO CONFIRMED — no strong substantial phone | " +
                                    "elapsedSinceStrongMs=$elapsedSinceStrong | " +
                                    "holdRemainingMs=$remainingMs"
                            )

                            if (elapsedSinceStrong >= YOLO_HOLD_MS) {
                                isYoloThreatConfirmed = false
                                lastStrongYoloElapsedMs = null
                                yoloDetectionHistory.clear()

                                Log.d(
                                    "YOLODetector",
                                    "YOLO THREAT CLEARED after ${YOLO_HOLD_MS}ms without strong evidence"
                                )
                            }
                        }
                    }

                    faceTask.addOnCompleteListener {
                        imageProxy.close()
                    }
                } catch (e: Exception) {
                    Log.e("GuardianLab", "Analyzer Error", e)
                    imageProxy.close()
                }
            }
        )
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            cameraManager.startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}