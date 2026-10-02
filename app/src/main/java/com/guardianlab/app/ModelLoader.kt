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

            // D1-L6G CONTROL:
            // NNAPI intentionally disabled.
            // Force ONNX Runtime CPU execution to determine whether
            // repeated-inference corruption is caused by NNAPI.

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