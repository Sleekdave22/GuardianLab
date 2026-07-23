package com.guardianlab.app

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
    private lateinit var faceCountText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var blurPanel: android.view.View
    private lateinit var shieldController: ShieldController
    private lateinit var cameraManager: CameraManager
    private lateinit var faceDetectionManager: FaceDetectionManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var guardianEngine: GuardianEngine
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraManager.startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        faceCountText = TextView(this).apply {
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(150, 0, 0, 0))
            text = "Faces: 0"
            setPadding(20, 40, 20, 40)
        }

        warningText = TextView(this).apply {
            textSize = 28f
            setTextColor(android.graphics.Color.RED)
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            text = ""
            setPadding(20, 120, 20, 40)
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
        layout.addView(faceCountText)
        layout.addView(warningText)
        layout.addView(shieldView)
        val blurParams = android.widget.FrameLayout.LayoutParams(
            700,
            300
        ).apply {
            gravity = android.view.Gravity.CENTER
        }

        layout.addView(blurPanel, blurParams)
        warningText.bringToFront()
        faceCountText.bringToFront()
        setContentView(layout)
        shieldController = ShieldController(
            shieldView,
            blurPanel,
            warningText
        )
        overlayManager = OverlayManager(
            faceCountText,
            warningText
        )
        guardianEngine = GuardianEngine()
        faceDetectionManager = FaceDetectionManager { count ->

            runOnUiThread {

                faceCountText.visibility = android.view.View.VISIBLE
                overlayManager.updateFaceCount(count)

                if (guardianEngine.shouldActivateProtection(count)) {
                    overlayManager.showWarning("⚠ ADDITIONAL VIEWER DETECTED")
                    shieldController.showProtection()
                } else {
                    overlayManager.clearWarning()
                    shieldController.hideProtectionWithDelay()
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraManager = CameraManager(
            lifecycleOwner = this,
            previewView = previewView,
            cameraExecutor = cameraExecutor,
            imageAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
                faceDetectionManager.process(imageProxy)
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