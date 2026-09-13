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
    private var lastYoloThreatTimestamp: Long = 0
    private val isThreatDetectedByYolo: Boolean 
        get() = (System.currentTimeMillis() - lastYoloThreatTimestamp) < 5000
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

                val shouldProtect = guardianEngine.shouldActivateProtection(count) || isThreatDetectedByYolo
                if (shouldProtect) {
                    val message = if (isThreatDetectedByYolo) "⚠ CAMERA DEVICE DETECTED" else "⚠ ADDITIONAL VIEWER DETECTED"
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
                    val yoloDetections = yoloDetector.detect(bitmap)

                    Log.d(
                        "YOLODetector",
                        "YOLO detections: ${yoloDetections.size}"
                    )

                    // GuardianLab YOLO detector: class 0 = phone
                    val currentFrameThreat = yoloDetections.any {
                        it.classId == 0 &&
                                it.className == "phone" &&
                                it.confidence >= 0.25f
                    }

                    if (currentFrameThreat) {
                        lastYoloThreatTimestamp = System.currentTimeMillis()
                        runOnUiThread {
                            overlayManager.showWarning("⚠ CAMERA DEVICE DETECTED")
                            shieldController.showProtection("⚠ CAMERA DEVICE DETECTED")
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