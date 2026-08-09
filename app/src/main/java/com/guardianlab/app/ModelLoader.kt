package com.guardianlab.app

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelLoader(context: Context) {

    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModel(context)
        interpreter = Interpreter(modelBuffer)
    }

    private fun loadModel(context: Context): ByteBuffer {
        val modelBytes = context.assets
            .open("yolo11n.tflite")
            .use { it.readBytes() }

        return ByteBuffer
            .allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(modelBytes)
                rewind()
            }
    }

    fun getInterpreter(): Interpreter {
        return interpreter
    }

    fun close() {
        interpreter.close()
    }
}