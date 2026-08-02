package com.guardianlab.app

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class PhoneDetectionManager(
    private val onPhoneDetected: (Boolean) -> Unit
) {

    private val detector by lazy {

        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(
                ObjectDetectorOptions.STREAM_MODE
            )
            .enableMultipleObjects()
            .enableClassification()
            .build()

        ObjectDetection.getClient(options)
    }

    fun process(image: InputImage): Task<List<DetectedObject>> {

        return detector.process(image)
            .addOnSuccessListener { detectedObjects ->

                var phoneFound = false

                for (detectedObject in detectedObjects) {

                    for (label in detectedObject.labels) {

                        Log.d(
                            "GuardianPhone",
                            "Detected: ${label.text}, " +
                                    "Confidence: ${label.confidence}"
                        )

                        if (
                            label.text.equals(
                                "Phone",
                                ignoreCase = true
                            )
                        ) {
                            phoneFound = true
                        }
                    }
                }

                onPhoneDetected(phoneFound)
            }
            .addOnFailureListener { e ->

                Log.e(
                    "GuardianPhone",
                    "Phone detection failed",
                    e
                )
            }
    }
}