package com.guardianlab.app

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter

class YOLODetector(
    private val modelLoader: ModelLoader
) {

    private val interpreter: Interpreter =
        modelLoader.getInterpreter()

    private val inputWidth: Int
    private val inputHeight: Int

    init {
        val inputShape = interpreter.getInputTensor(0).shape()

        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
    }

    fun getInputWidth(): Int {
        return inputWidth
    }

    fun getInputHeight(): Int {
        return inputHeight
    }

    fun close() {
        modelLoader.close()
    }
}