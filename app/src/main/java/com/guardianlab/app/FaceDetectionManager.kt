package com.guardianlab.app

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
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

    fun process(image: InputImage): Task<List<Face>> {

        return detector.process(image)
            .addOnSuccessListener { faces ->

                Log.d(
                    "GuardianFace",
                    "Faces detected: ${faces.size}"
                )

                onFaceCountChanged(faces.size)
            }
            .addOnFailureListener { e ->

                Log.e(
                    "GuardianFace",
                    "Face detection failed",
                    e
                )
            }
    }
}