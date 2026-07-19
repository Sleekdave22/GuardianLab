package com.guardianlab.app

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
class FaceDetectionManager(

    private val onFaceCountChanged: (Int) -> Unit

) {

    private val detector by lazy {

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(
                FaceDetectorOptions.PERFORMANCE_MODE_FAST
            )
            .build()

        FaceDetection.getClient(options)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun process(imageProxy: ImageProxy) {

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

                onFaceCountChanged(faces.size)

            }
            .addOnCompleteListener {

                imageProxy.close()

            }
    }
}