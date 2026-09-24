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
    private var consecutiveYoloMisses = 0

    private companion object {
        const val YOLO_STRONG_CONFIDENCE = 0.45f
        const val YOLO_WEAK_CONFIDENCE = 0.30f

        const val YOLO_CONFIRMATION_WINDOW = 3
        const val YOLO_CONFIRMATION_REQUIRED = 2

        const val YOLO_MISSES_TO_CLEAR = 5
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

                    Log.d(
                        "YOLODetector",
                        "YOLO INPUT: avgRGB=($avgR,$avgG,$avgB), brightness=${"%.1f".format(avgBrightness)}"
                    )
                    val yoloDetections = yoloDetector.detect(bitmap)

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

                    val hasStrongPhoneDetection = yoloDetections.any {
                        it.classId == 0 &&
                        it.className == "phone" &&
                        it.confidence >= YOLO_STRONG_CONFIDENCE
                    }

                    val hasWeakPhoneDetection = yoloDetections.any {
                        it.classId == 0 &&
                        it.className == "phone" &&
                        it.confidence >= YOLO_WEAK_CONFIDENCE
                    }

                    if (!isYoloThreatConfirmed) {
                        // We are not yet protecting.
                        // Only strong detections can establish the initial threat.
                        yoloDetectionHistory.addLast(hasWeakPhoneDetection)

                        if (yoloDetectionHistory.size > YOLO_CONFIRMATION_WINDOW) {
                            yoloDetectionHistory.removeFirst()
                        }

                        val confirmedDetections = yoloDetectionHistory.count { it }

                        if (confirmedDetections >= YOLO_CONFIRMATION_REQUIRED) {
                            isYoloThreatConfirmed = true
                            consecutiveYoloMisses = 0

                            Log.d(
                                "YOLODetector",
                                "YOLO THREAT CONFIRMED: history=$yoloDetectionHistory"
                            )

                            runOnUiThread {
                                overlayManager.showWarning("⚠ CAMERA DEVICE DETECTED")
                                shieldController.showProtection("⚠ CAMERA DEVICE DETECTED")
                            }
                        } else {
                            Log.d(
                                "YOLODetector",
                                "YOLO waiting for confirmation: " +
                                    "history=$yoloDetectionHistory " +
                                    "strongDetections=$confirmedDetections"
                            )
                        }
                    } else {
                        // A phone has already been confirmed.
                        //
                        // Strong detection OR weak detection means the phone is
                        // still visible enough to maintain protection.
                        //
                        // Only a completely missing phone detection counts as a miss.

                        if (hasStrongPhoneDetection) {
                            consecutiveYoloMisses = 0

                            Log.d(
                                "YOLODetector",
                                "YOLO CONFIRMED — strong phone detection"
                            )

                            runOnUiThread {
                                overlayManager.showWarning("⚠ CAMERA DEVICE DETECTED")
                                shieldController.showProtection("⚠ CAMERA DEVICE DETECTED")
                            }

                        } else if (hasWeakPhoneDetection) {
                            consecutiveYoloMisses = 0

                            val weakDetection = yoloDetections
                                .filter {
                                    it.classId == 0 &&
                                        it.className == "phone" &&
                                        it.confidence >= YOLO_WEAK_CONFIDENCE
                                }
                                .maxByOrNull { it.confidence }

                            Log.d(
                                "YOLODetector",
                                "YOLO CONFIRMED — weak phone detection " +
                                    "confidence=${weakDetection?.confidence}"
                            )

                            runOnUiThread {
                                overlayManager.showWarning("⚠ CAMERA DEVICE DETECTED")
                                shieldController.showProtection("⚠ CAMERA DEVICE DETECTED")
                            }

                        } else {
                            // No phone detection at all.
                            consecutiveYoloMisses++

                            Log.d(
                                "YOLODetector",
                                "YOLO CONFIRMED — no phone detected, " +
                                    "missed frame $consecutiveYoloMisses/$YOLO_MISSES_TO_CLEAR"
                            )

                            if (consecutiveYoloMisses >= YOLO_MISSES_TO_CLEAR) {
                                isYoloThreatConfirmed = false
                                consecutiveYoloMisses = 0
                                yoloDetectionHistory.clear()

                                Log.d(
                                    "YOLODetector",
                                    "YOLO THREAT CLEARED after consecutive misses"
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