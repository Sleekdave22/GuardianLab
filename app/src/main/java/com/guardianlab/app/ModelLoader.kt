package com.guardianlab.app

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

class ModelLoader(context: Context) {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession

    init {
        val modelBytes = context.assets
            .open("yolo11n_v2_phone_detector.onnx")
            .use { it.readBytes() }

        val sessionOptions = OrtSession.SessionOptions().apply {
            try {
                // Offload model compilation and layers execution to NPU/GPU hardware
                addNnapi()
            } catch (e: Exception) {
                android.util.Log.w("ModelLoader", "NNAPI hardware acceleration not supported on this device, falling back to CPU", e)
            }
            // Optimize memory management for video streams
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        session = environment.createSession(
            modelBytes,
            sessionOptions
        )
    }

    fun getSession(): OrtSession {
        return session
    }

    fun getEnvironment(): OrtEnvironment {
        return environment
    }

    fun close() {
        session.close()
    }
}