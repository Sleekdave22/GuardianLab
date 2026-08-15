package com.guardianlab.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

class YOLODetector(
    private val modelLoader: ModelLoader
) {

    private val interpreter: Interpreter =
        modelLoader.getInterpreter()

    private val inputWidth: Int
    private val inputHeight: Int

    private val cocoNames = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed",
        "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    init {
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()

        inputHeight = inputShape[2]
        inputWidth = inputShape[3]

        Log.d(
            "YOLODetector",
            "INIT: inputShape=${inputShape.contentToString()}, " +
                    "outputShape=${interpreter.getOutputTensor(0).shape().contentToString()}"
        )
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val startTime = System.currentTimeMillis()

        Log.d("YOLODetector", "MODEL INPUT SHAPE: ${interpreter.getInputTensor(0).shape().contentToString()}")
        Log.d("YOLODetector", "MODEL INPUT TYPE: ${interpreter.getInputTensor(0).dataType()}")
        Log.d("YOLODetector", "MODEL OUTPUT SHAPE: ${interpreter.getOutputTensor(0).shape().contentToString()}")
        Log.d("YOLODetector", "DETECTOR DIMENSIONS: width=$inputWidth, height=$inputHeight")

        // 1. Preprocessing: Letterbox (Maintain Aspect Ratio) + Horizontal Flip (Un-mirror)
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        Log.d("YOLODetector", "BITMAP BEFORE LETTERBOX: width=$bitmapWidth, height=$bitmapHeight")

        val scale = minOf(inputWidth.toFloat() / bitmapWidth, inputHeight.toFloat() / bitmapHeight)
        val nw = (bitmapWidth * scale).toInt()
        val nh = (bitmapHeight * scale).toInt()

        val letterboxBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxBitmap)
        canvas.drawColor(Color.rgb(128, 128, 128)) // Gray padding
        Log.d("YOLODetector", "BITMAP AFTER LETTERBOX: width=${letterboxBitmap.width}, height=${letterboxBitmap.height}")

        val left = (inputWidth - nw) / 2f
        val top = (inputHeight - nh) / 2f

        val matrix = Matrix().apply {
            // Resize
            postScale(scale, scale)
            // Un-mirror (Horizontal Flip) since front camera is mirrored
            postScale(-1f, 1f, (nw / 2f), (nh / 2f))
            // Center in 640x640
            postTranslate(left, top)
        }
        
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, matrix, paint)

        Log.d("YOLODetector", "LETTERBOX: scale=$scale, padLeft=$left, padTop=$top, orig=${bitmapWidth}x${bitmapHeight}, target=${nw}x${nh}")

        val inputBuffer = ByteBuffer
            .allocateDirect(1 * 3 * inputWidth * inputHeight * 4)
            .order(ByteOrder.nativeOrder())
        Log.d("YOLODetector", "INPUT BUFFER EXPECTS: width=$inputWidth, height=$inputHeight, channels=3")

        val pixels = IntArray(inputWidth * inputHeight)
        letterboxBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        // Statistics tracking
        var sumR = 0f; var sumG = 0f; var sumB = 0f
        var minR = 1f; var maxR = 0f
        var minG = 1f; var maxG = 0f
        var minB = 1f; var maxB = 0f

        /*
         * NORMALIZATION TEST:
         * Standard YOLOv11 usually expects [0, 1].
         * If results are extremely low, change 255.0f to 1.0f below.
         */
        val normFactor = 255.0f 

        // Pack in NCHW order and track stats
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / normFactor
            inputBuffer.putFloat(r)
            sumR += r; minR = minOf(minR, r); maxR = maxOf(maxR, r)
        }
        for (pixel in pixels) {
            val g = ((pixel shr 8) and 0xFF) / normFactor
            inputBuffer.putFloat(g)
            sumG += g; minG = minOf(minG, g); maxG = maxOf(maxG, g)
        }
        for (pixel in pixels) {
            val b = (pixel and 0xFF) / normFactor
            inputBuffer.putFloat(b)
            sumB += b; minB = minOf(minB, b); maxB = maxOf(maxB, b)
        }
        
        val count = (inputWidth * inputHeight).toFloat()
        Log.d("YOLODetector", "INPUT STATS (Norm=$normFactor): R[avg=${sumR/count}, min=$minR, max=$maxR], G[avg=${sumG/count}, min=$minG, max=$maxG], B[avg=${sumB/count}, min=$minB, max=$maxB]")

        inputBuffer.rewind()

        // 2. Run Inference
        val outputShape = interpreter.getOutputTensor(0).shape()
        val channels = outputShape[1] // 84 (4 boxes + 80 classes)
        val candidates = outputShape[2] // 8400

        val output = Array(1) {
            Array(channels) {
                FloatArray(candidates)
            }
        }

        interpreter.run(inputBuffer, output)

        // 3. Diagnostic Logging: Top 10 Class Scores
        val topScores = PriorityQueue<RawScore>(10) { a, b -> a.score.compareTo(b.score) }
        var cellPhoneMaxScore = 0f
        var cellPhoneMaxCandidate = -1

        for (candidate in 0 until candidates) {
            // Specifically track ClassID 67 (cell phone)
            val score67 = output[0][67 + 4][candidate]
            if (score67 > cellPhoneMaxScore) {
                cellPhoneMaxScore = score67
                cellPhoneMaxCandidate = candidate
            }

            for (classId in 0 until channels - 4) {
                val score = output[0][classId + 4][candidate]
                if (topScores.size < 10 || score > topScores.peek()!!.score) {
                    if (topScores.size == 10) topScores.poll()
                    topScores.add(RawScore(score, classId, candidate))
                }
            }
        }

        Log.d("YOLODetector", "CELL PHONE MAX SCORE: $cellPhoneMaxScore, Candidate: $cellPhoneMaxCandidate")
        
        if (cellPhoneMaxCandidate != -1) {
             Log.d("YOLODetector", "CELL PHONE RAW BOX: " +
                "cx=${output[0][0][cellPhoneMaxCandidate]}, " +
                "cy=${output[0][1][cellPhoneMaxCandidate]}, " +
                "w=${output[0][2][cellPhoneMaxCandidate]}, " +
                "h=${output[0][3][cellPhoneMaxCandidate]}")
        }

        Log.d("YOLODetector", "--- Top 10 Raw Scores ---")
        topScores.toList().sortedByDescending { it.score }.forEachIndexed { i, raw ->
            val name = if (raw.classId < cocoNames.size) cocoNames[raw.classId] else "unknown"
            Log.d("YOLODetector", "#$i: $name (${raw.classId}), Score=${raw.score}, Candidate=${raw.candidate}")
        }

        // 4. Box Decoding
        val allDetections = mutableListOf<DetectionResult>()
        val confidenceThreshold = 0.25f

        for (candidate in 0 until candidates) {
            var bestClassId = -1
            var maxConfidence = 0f

            for (classId in 0 until channels - 4) {
                val confidence = output[0][classId + 4][candidate]
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classId
                }
            }

            if (maxConfidence >= confidenceThreshold) {
                // YOLO output is normalized [0, 1] relative to input dimensions (640)
                val cx = output[0][0][candidate] * inputWidth
                val cy = output[0][1][candidate] * inputHeight
                val w = output[0][2][candidate] * inputWidth
                val h = output[0][3][candidate] * inputHeight

                // Convert from center coordinates to corners in 640x640 space
                val x1_640 = cx - w / 2f
                val y1_640 = cy - h / 2f
                val x2_640 = cx + w / 2f
                val y2_640 = cy + h / 2f

                // Correct for letterbox padding and scale back to original bitmap size
                // Note: Horizontal flip is un-mirrored here because the detection logic works on the flipped pixels.
                // The boxes should map back to the 'upright' bitmap we passed in from MainActivity.
                val x1 = (x1_640 - left) / scale
                val y1 = (y1_640 - top) / scale
                val x2 = (x2_640 - left) / scale
                val y2 = (y2_640 - top) / scale

                allDetections.add(
                    DetectionResult(
                        classId = bestClassId,
                        className = cocoNames[bestClassId],
                        confidence = maxConfidence,
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2
                    )
                )
            }
        }

        Log.d("YOLODetector", "Detections before NMS: ${allDetections.size}")
        if (allDetections.isNotEmpty()) {
            val strongest = allDetections.maxByOrNull { it.confidence }!!
            Log.d("YOLODetector", "Strongest BEFORE NMS: ${strongest.className}, Conf=${strongest.confidence}, Box=[${strongest.x1}, ${strongest.y1}, ${strongest.x2}, ${strongest.y2}]")
        }

        // 5. NMS
        val finalDetections = nms(allDetections, 0.45f)
        Log.d("YOLODetector", "Detections after NMS: ${finalDetections.size}")
        if (finalDetections.isNotEmpty()) {
            val strongest = finalDetections.maxByOrNull { it.confidence }!!
            Log.d("YOLODetector", "Strongest AFTER NMS: ${strongest.className}, Conf=${strongest.confidence}, Box=[${strongest.x1}, ${strongest.y1}, ${strongest.x2}, ${strongest.y2}]")
        }

        val totalTime = System.currentTimeMillis() - startTime
        Log.d("YOLODetector", "Total processing time: ${totalTime}ms")

        return finalDetections
    }

    private fun nms(detections: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val results = mutableListOf<DetectionResult>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            results.add(best)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(best, next) >= iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return results
    }

    private fun calculateIoU(a: DetectionResult, b: DetectionResult): Float {
        val x1 = maxOf(a.x1, b.x1)
        val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2)
        val y2 = minOf(a.y2, b.y2)
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        return intersection / (areaA + areaB - intersection)
    }

    private data class RawScore(val score: Float, val classId: Int, val candidate: Int)

    fun getInputWidth() = inputWidth
    fun getInputHeight() = inputHeight
    fun close() { modelLoader.close() }
}
