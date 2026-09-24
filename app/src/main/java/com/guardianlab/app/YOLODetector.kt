package com.guardianlab.app

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YOLODetector(
    private val modelLoader: ModelLoader
) {

    companion object {
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.30f
        private const val PROPOSAL_CONFIDENCE_THRESHOLD = 0.001f
        private const val PROPOSAL_TOP_K = 10
        private const val NMS_IOU_THRESHOLD = 0.50f
        private const val PADDING_VALUE = 114f
        private const val CLASS_ID = 0
        private const val CLASS_NAME = "phone"
    }

    private val environment: OrtEnvironment =
        modelLoader.getEnvironment()

    private val session: OrtSession =
        modelLoader.getSession()

    fun detect(bitmap: Bitmap): List<DetectionResult> {

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) {
            return emptyList()
        }

        // ------------------------------------------------------------
        // 1. Letterbox image to 640 x 640
        // ------------------------------------------------------------

        val scale = min(
            INPUT_SIZE.toFloat() / originalWidth.toFloat(),
            INPUT_SIZE.toFloat() / originalHeight.toFloat()
        )

        val resizedWidth = (originalWidth * scale).toInt()
        val resizedHeight = (originalHeight * scale).toInt()

        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap,
            resizedWidth,
            resizedHeight,
            true
        )

        val padX = (INPUT_SIZE - resizedWidth) / 2f
        val padY = (INPUT_SIZE - resizedHeight) / 2f

        // ------------------------------------------------------------
        // 2. Build NCHW float input
        //
        // Model expects:
        // [1, 3, 640, 640]
        //
        // Channel order:
        // R, G, B
        //
        // Values:
        // 0.0 - 1.0
        // ------------------------------------------------------------

        val input = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE) {
            PADDING_VALUE / 255f
        }

        val pixels = IntArray(resizedWidth * resizedHeight)

        resizedBitmap.getPixels(
            pixels,
            0,
            resizedWidth,
            0,
            0,
            resizedWidth,
            resizedHeight
        )

        val planeSize = INPUT_SIZE * INPUT_SIZE

        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {

                val pixel = pixels[y * resizedWidth + x]

                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                val dstX = x + padX.toInt()
                val dstY = y + padY.toInt()

                val index = dstY * INPUT_SIZE + dstX

                input[index] = r
                input[planeSize + index] = g
                input[(planeSize * 2) + index] = b
            }
        }

        // ------------------------------------------------------------
        // 3. Run ONNX model
        // ------------------------------------------------------------

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val results = session.run(
            mapOf("images" to inputTensor)
        )

        try {

            // YOLO11n deployment output:
            // [1, 5, 8400]
            //
            // 5 values:
            // x_center
            // y_center
            // width
            // height
            // phone_confidence

            val output = results[0].value as Array<Array<FloatArray>>

            val detections = mutableListOf<DetectionResult>()

            for (i in 0 until 8400) {

                val xCenter = output[0][0][i]
                val yCenter = output[0][1][i]
                val width = output[0][2][i]
                val height = output[0][3][i]
                val confidence = output[0][4][i]

                if (confidence < CONFIDENCE_THRESHOLD) {
                    continue
                }

                // ----------------------------------------------------
                // Convert xywh -> xyxy in 640-space
                // ----------------------------------------------------

                var x1 = xCenter - width / 2f
                var y1 = yCenter - height / 2f
                var x2 = xCenter + width / 2f
                var y2 = yCenter + height / 2f

                // ----------------------------------------------------
                // Undo letterbox
                // ----------------------------------------------------

                x1 = (x1 - padX) / scale
                y1 = (y1 - padY) / scale
                x2 = (x2 - padX) / scale
                y2 = (y2 - padY) / scale

                // ----------------------------------------------------
                // Clip to original image
                // ----------------------------------------------------

                x1 = x1.coerceIn(0f, originalWidth.toFloat())
                y1 = y1.coerceIn(0f, originalHeight.toFloat())
                x2 = x2.coerceIn(0f, originalWidth.toFloat())
                y2 = y2.coerceIn(0f, originalHeight.toFloat())

                if (x2 <= x1 || y2 <= y1) {
                    continue
                }

                detections.add(
                    DetectionResult(
                        classId = CLASS_ID,
                        className = CLASS_NAME,
                        confidence = confidence,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2
                    )
                )
            }

            // --------------------------------------------------------
            // 4. Non-Maximum Suppression (NMS)
            // --------------------------------------------------------

            return applyNms(
                detections,
                NMS_IOU_THRESHOLD
            )

        } finally {

            results.close()
            inputTensor.close()
            resizedBitmap.recycle()
        }
    }

    fun detectProposals(bitmap: Bitmap): List<DetectionResult> {

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) {
            return emptyList()
        }

        val scale = min(
            INPUT_SIZE.toFloat() / originalWidth.toFloat(),
            INPUT_SIZE.toFloat() / originalHeight.toFloat()
        )

        val resizedWidth = (originalWidth * scale).toInt()
        val resizedHeight = (originalHeight * scale).toInt()

        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap,
            resizedWidth,
            resizedHeight,
            true
        )

        val padX = (INPUT_SIZE - resizedWidth) / 2f
        val padY = (INPUT_SIZE - resizedHeight) / 2f

        val input = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE) {
            PADDING_VALUE / 255f
        }

        val pixels = IntArray(resizedWidth * resizedHeight)

        resizedBitmap.getPixels(
            pixels,
            0,
            resizedWidth,
            0,
            0,
            resizedWidth,
            resizedHeight
        )

        val planeSize = INPUT_SIZE * INPUT_SIZE

        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {

                val pixel = pixels[y * resizedWidth + x]

                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                val dstX = x + padX.toInt()
                val dstY = y + padY.toInt()

                val index = dstY * INPUT_SIZE + dstX

                input[index] = r
                input[planeSize + index] = g
                input[(planeSize * 2) + index] = b
            }
        }

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(
                1,
                3,
                INPUT_SIZE.toLong(),
                INPUT_SIZE.toLong()
            )
        )

        val results = session.run(
            mapOf("images" to inputTensor)
        )

        try {

            val output =
                results[0].value as Array<Array<FloatArray>>

            val proposals = mutableListOf<DetectionResult>()

            for (i in 0 until 8400) {

                val confidence = output[0][4][i]

                if (confidence < PROPOSAL_CONFIDENCE_THRESHOLD) {
                    continue
                }

                val xCenter = output[0][0][i]
                val yCenter = output[0][1][i]
                val width = output[0][2][i]
                val height = output[0][3][i]

                var x1 = xCenter - width / 2f
                var y1 = yCenter - height / 2f
                var x2 = xCenter + width / 2f
                var y2 = yCenter + height / 2f

                x1 = (x1 - padX) / scale
                y1 = (y1 - padY) / scale
                x2 = (x2 - padX) / scale
                y2 = (y2 - padY) / scale

                x1 = x1.coerceIn(0f, originalWidth.toFloat())
                y1 = y1.coerceIn(0f, originalHeight.toFloat())
                x2 = x2.coerceIn(0f, originalWidth.toFloat())
                y2 = y2.coerceIn(0f, originalHeight.toFloat())

                if (x2 <= x1 || y2 <= y1) {
                    continue
                }

                proposals.add(
                    DetectionResult(
                        classId = CLASS_ID,
                        className = CLASS_NAME,
                        confidence = confidence,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2
                    )
                )
            }

            return applyNms(
                proposals,
                NMS_IOU_THRESHOLD
            )
                .sortedByDescending { it.confidence }
                .take(PROPOSAL_TOP_K)

        } finally {

            results.close()
            inputTensor.close()
            resizedBitmap.recycle()
        }
    }

    private fun applyNms(
        detections: List<DetectionResult>,
        iouThreshold: Float
    ): List<DetectionResult> {

        if (detections.isEmpty()) {
            return emptyList()
        }

        val sorted = detections
            .sortedByDescending { it.confidence }
            .toMutableList()

        val selected = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {

            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()

            while (iterator.hasNext()) {

                val candidate = iterator.next()

                if (calculateIoU(best, candidate) > iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return selected
    }

    private fun calculateIoU(
        a: DetectionResult,
        b: DetectionResult
    ): Float {

        val intersectionX1 = max(a.x1, b.x1)
        val intersectionY1 = max(a.y1, b.y1)
        val intersectionX2 = min(a.x2, b.x2)
        val intersectionY2 = min(a.y2, b.y2)

        val intersectionWidth =
            max(0f, intersectionX2 - intersectionX1)

        val intersectionHeight =
            max(0f, intersectionY2 - intersectionY1)

        val intersectionArea =
            intersectionWidth * intersectionHeight

        val areaA =
            max(0f, a.x2 - a.x1) *
                    max(0f, a.y2 - a.y1)

        val areaB =
            max(0f, b.x2 - b.x1) *
                    max(0f, b.y2 - b.y1)

        val unionArea =
            areaA + areaB - intersectionArea

        if (unionArea <= 0f) {
            return 0f
        }

        return intersectionArea / unionArea
    }
}