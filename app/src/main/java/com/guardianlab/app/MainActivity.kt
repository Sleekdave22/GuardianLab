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

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val hideShieldRunnable = Runnable {
        shieldView.visibility = android.view.View.GONE
        blurPanel.visibility = android.view.View.GONE
        warningText.text = ""
    }
    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        FaceDetection.getClient(options)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this)
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
            setBackgroundColor(android.graphics.Color.argb(220, 0, 0, 0))
            visibility = android.view.View.GONE
        }
        blurPanel = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.argb(220, 50, 50, 50))
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
        setContentView(layout)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(image)
            .addOnSuccessListener { faces ->
                runOnUiThread {

                    // TEST VALUE
                    // Change this to 1 or 2 while testing.
                    val count = faces.size
                    // Later replace with:
                    // val count = faces.size

                    faceCountText.text = "Faces: $count"

                    if (count >= 2) {

                        handler.removeCallbacks(hideShieldRunnable)

                        warningText.text = "⚠ ADDITIONAL VIEWER DETECTED"

                        shieldView.visibility = android.view.View.VISIBLE
                        blurPanel.visibility = android.view.View.VISIBLE

                    } else {

                        handler.removeCallbacks(hideShieldRunnable)

                        if (shieldView.visibility == android.view.View.VISIBLE) {

                            handler.postDelayed(
                                hideShieldRunnable,
                                3000
                            )

                        } else {

                            warningText.text = ""

                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}