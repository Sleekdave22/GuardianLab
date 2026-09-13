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

        val sessionOptions = OrtSession.SessionOptions()

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