package com.guardianlab.app

import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

data class YoloFrameResult(
    val detections: List<DetectionResult>,
    val proposals: List<DetectionResult>
)

data class DiagnosticSummary(
    val totalInferredFrames: Int,
    val detectionFrames: Int,
    val maxRawConf: Float,
    val maxProposalConf: Float,
    val savedPngCount: Int,
    val durationMs: Long
)

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

    private val diagnosticActive = AtomicBoolean(false)
    private val diagnosticFrameCounter = AtomicLong(0L)
    private var diagnosticFilesDir: File? = null
    private var diagnosticStartElapsed = 0L

    private var prevRawMax = 0f
    private var prevHasDetections = false

    private var runInferredFrames = 0
    private var runDetectionFrames = 0
    private var runMaxRawConf = 0f
    private var runMaxProposalConf = 0f
    private var runSavedPngCount = 0

    private val diagnosticScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startDiagnostic(filesDir: File): Long {
        diagnosticFilesDir = File(filesDir, "d1_l8c")
        if (diagnosticFilesDir!!.exists()) {
            diagnosticFilesDir!!.deleteRecursively()
        }
        diagnosticFilesDir!!.mkdirs()

        diagnosticFrameCounter.set(0L)
        prevRawMax = 0f
        prevHasDetections = false
        runInferredFrames = 0
        runDetectionFrames = 0
        runMaxRawConf = 0f
        runMaxProposalConf = 0f
        runSavedPngCount = 0
        diagnosticStartElapsed = android.os.SystemClock.elapsedRealtime()

        diagnosticActive.set(true)
        return diagnosticStartElapsed
    }

    fun stopDiagnostic(): DiagnosticSummary? {
        if (!diagnosticActive.getAndSet(false)) {
            return null
        }
        val duration = android.os.SystemClock.elapsedRealtime() - diagnosticStartElapsed
        val summary = DiagnosticSummary(
            totalInferredFrames = runInferredFrames,
            detectionFrames = runDetectionFrames,
            maxRawConf = runMaxRawConf,
            maxProposalConf = runMaxProposalConf,
            savedPngCount = runSavedPngCount,
            durationMs = duration
        )

        Log.d(
            "GL_YOLO_SEQUENCE",
            "D1-L8C SUMMARY | totalFrames=${summary.totalInferredFrames} | " +
                "detectionFrames=${summary.detectionFrames} | " +
                "maxRawConf=${"%.6f".format(Locale.US, summary.maxRawConf)} | " +
                "maxProposalConf=${"%.6f".format(Locale.US, summary.maxProposalConf)} | " +
                "savedPngs=${summary.savedPngCount} | " +
                "durationMs=${summary.durationMs}"
        )

        return summary
    }

    fun isDiagnosticActive(): Boolean = diagnosticActive.get()

    fun detectFrame(bitmap: Bitmap): YoloFrameResult {

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) {
            return YoloFrameResult(
                detections = emptyList(),
                proposals = emptyList()
            )
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

        // Capture diagnostic active status at start of inference pass
        val capturingForThisFrame = diagnosticActive.get()
        val frameId = if (capturingForThisFrame) diagnosticFrameCounter.incrementAndGet() else 0L

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

            val output = results[0].value as Array<Array<FloatArray>>

            val detections = mutableListOf<DetectionResult>()
            val proposalCandidates = mutableListOf<DetectionResult>()

            var rawTop = 0f
            for (i in 0 until 8400) {
                val c = output[0][4][i]
                if (c.isFinite() && c > rawTop) {
                    rawTop = c
                }
            }

            for (i in 0 until 8400) {

                val xCenter = output[0][0][i]
                val yCenter = output[0][1][i]
                val width = output[0][2][i]
                val height = output[0][3][i]
                val confidence = output[0][4][i]

                if (
                    !xCenter.isFinite() ||
                    !yCenter.isFinite() ||
                    !width.isFinite() ||
                    !height.isFinite() ||
                    !confidence.isFinite()
                ) {
                    continue
                }

                val isDetection = confidence >= CONFIDENCE_THRESHOLD
                val isProposal = confidence >= PROPOSAL_CONFIDENCE_THRESHOLD

                if (!isDetection && !isProposal) {
                    continue
                }

                var x1 = xCenter - width / 2f
                var y1 = yCenter - height / 2f
                var x2 = xCenter + width / 2f
                var y2 = yCenter + height / 2f

                // D1-L8M padding-boundary audit.
                // Measure how much of the raw YOLO box lies inside
                // the real image-content region before unletterboxing.
                if (isDetection && diagnosticActive.get()) {

                    val contentLeft = padX
                    val contentTop = padY
                    val contentRight = padX + resizedWidth
                    val contentBottom = padY + resizedHeight

                    val overlapLeft = maxOf(x1, contentLeft)
                    val overlapTop = maxOf(y1, contentTop)
                    val overlapRight = minOf(x2, contentRight)
                    val overlapBottom = minOf(y2, contentBottom)

                    val boxWidth = maxOf(0f, x2 - x1)
                    val boxHeight = maxOf(0f, y2 - y1)

                    val overlapWidth =
                        maxOf(0f, overlapRight - overlapLeft)

                    val overlapHeight =
                        maxOf(0f, overlapBottom - overlapTop)

                    val boxArea =
                        boxWidth * boxHeight

                    val overlapArea =
                        overlapWidth * overlapHeight

                    val contentFraction =
                        if (boxArea > 0f) {
                            overlapArea / boxArea
                        } else {
                            0f
                        }

                    val wouldRejectAt25 =
                        contentFraction < 0.25f

                    Log.d(
                        "GL_PADDING_AUDIT",
                        "D1-L8M | conf=${"%.6f".format(Locale.US, confidence)} | " +
                            "modelBox=(${"%.1f".format(Locale.US, x1)}," +
                            "${"%.1f".format(Locale.US, y1)}," +
                            "${"%.1f".format(Locale.US, x2)}," +
                            "${"%.1f".format(Locale.US, y2)}) | " +
                            "contentRect=(${"%.1f".format(Locale.US, contentLeft)}," +
                            "${"%.1f".format(Locale.US, contentTop)}," +
                            "${"%.1f".format(Locale.US, contentRight)}," +
                            "${"%.1f".format(Locale.US, contentBottom)}) | " +
                            "contentFraction=${"%.4f".format(Locale.US, contentFraction)} | " +
                            "wouldReject25=$wouldRejectAt25"
                    )
                }

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

                val det = DetectionResult(
                    classId = CLASS_ID,
                    className = CLASS_NAME,
                    confidence = confidence,
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2
                )

                if (isDetection) {
                    detections.add(det)
                }
                if (isProposal) {
                    proposalCandidates.add(det)
                }
            }

            // --------------------------------------------------------
            // 4. Non-Maximum Suppression (NMS)
            // --------------------------------------------------------

            val finalDetections = applyNms(detections, NMS_IOU_THRESHOLD)
            val finalProposals = applyNms(proposalCandidates, NMS_IOU_THRESHOLD)
                .sortedByDescending { it.confidence }
                .take(PROPOSAL_TOP_K)

            // D1-L8C Synchronized Sequential Logging & Capture
            if (capturingForThisFrame) {
                runInferredFrames++
                if (finalDetections.isNotEmpty()) {
                    runDetectionFrames++
                }
                if (rawTop > runMaxRawConf) {
                    runMaxRawConf = rawTop
                }
                val proposalTop = finalProposals.maxOfOrNull { it.confidence } ?: 0f
                if (proposalTop > runMaxProposalConf) {
                    runMaxProposalConf = proposalTop
                }

                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                for (i in 0 until planeSize) {
                    sumR += input[i] * 255.0
                    sumG += input[planeSize + i] * 255.0
                    sumB += input[(planeSize * 2) + i] * 255.0
                }
                val avgR = (sumR / planeSize).toInt()
                val avgG = (sumG / planeSize).toInt()
                val avgB = (sumB / planeSize).toInt()
                val brightness = (0.299 * avgR) + (0.587 * avgG) + (0.114 * avgB)

                val timestamp = android.os.SystemClock.elapsedRealtime()
                val frameIdStr = String.format(Locale.US, "%06d", frameId)
                val detectionTopStr = if (finalDetections.isNotEmpty()) {
                    "%.6f".format(Locale.US, finalDetections.maxOf { it.confidence })
                } else {
                    "NONE"
                }

                val topProposal = finalProposals.maxByOrNull { it.confidence }

                val boxStr = if (topProposal != null) {
                    "%.1f,%.1f,%.1f,%.1f".format(
                        Locale.US,
                        topProposal.x1,
                        topProposal.y1,
                        topProposal.x2,
                        topProposal.y2
                    )
                } else {
                    "NONE"
                }

                // ------------------------------------------------------------
                // D1 TEMPORAL DIAGNOSTIC
                //
                // finalProposals are already mapped back into real camera
                // coordinates, so letterbox padding has already been removed.
                //
                // Padding-related hallucinations become very narrow edge boxes,
                // typically around 16-20 px wide.
                //
                // For this diagnostic only, call a proposal "substantial content"
                // when it is at least 50 px wide AND 50 px high.
                // ------------------------------------------------------------

                val contentProposals = finalProposals.filter { proposal ->

                    val width = proposal.x2 - proposal.x1
                    val height = proposal.y2 - proposal.y1

                    width >= 50f && height >= 50f
                }

                val contentTop =
                    contentProposals.maxByOrNull { it.confidence }

                val contentTopStr = if (contentTop != null) {
                    "%.6f".format(
                        Locale.US,
                        contentTop.confidence
                    )
                } else {
                    "NONE"
                }

                val contentBoxStr = if (contentTop != null) {
                    "%.1f,%.1f,%.1f,%.1f".format(
                        Locale.US,
                        contentTop.x1,
                        contentTop.y1,
                        contentTop.x2,
                        contentTop.y2
                    )
                } else {
                    "NONE"
                }

                val scaleTop =
                    finalProposals.maxByOrNull { it.confidence }

                val scaleBoxWidth =
                    scaleTop?.let { it.x2 - it.x1 }

                val scaleBoxHeight =
                    scaleTop?.let { it.y2 - it.y1 }

                val scaleWidthRatio =
                    scaleBoxWidth?.let {
                        it / originalWidth.toFloat()
                    }

                val scaleHeightRatio =
                    scaleBoxHeight?.let {
                        it / originalHeight.toFloat()
                    }

                val scaleAreaRatio =
                    if (
                        scaleBoxWidth != null &&
                        scaleBoxHeight != null &&
                        originalWidth > 0 &&
                        originalHeight > 0
                    ) {
                        (scaleBoxWidth * scaleBoxHeight) /
                            (originalWidth.toFloat() * originalHeight.toFloat())
                    } else {
                        null
                    }

                val scaleBoxWidthStr =
                    scaleBoxWidth?.let {
                        "%.1f".format(Locale.US, it)
                    } ?: "NONE"

                val scaleBoxHeightStr =
                    scaleBoxHeight?.let {
                        "%.1f".format(Locale.US, it)
                    } ?: "NONE"

                val scaleWidthRatioStr =
                    scaleWidthRatio?.let {
                        "%.4f".format(Locale.US, it)
                    } ?: "NONE"

                val scaleHeightRatioStr =
                    scaleHeightRatio?.let {
                        "%.4f".format(Locale.US, it)
                    } ?: "NONE"

                val scaleAreaRatioStr =
                    scaleAreaRatio?.let {
                        "%.4f".format(Locale.US, it)
                    } ?: "NONE"

                Log.d(
                    "GL_YOLO_SEQUENCE",
                    "D1-L8C | " +
                        "frame=$frameIdStr | " +
                        "t=$timestamp | " +
                        "rgb=($avgR,$avgG,$avgB) | " +
                        "brightness=${"%.1f".format(Locale.US, brightness)} | " +
                        "rawTop=${"%.6f".format(Locale.US, rawTop)} | " +
                        "proposalTop=${"%.6f".format(Locale.US, proposalTop)} | " +
                        "proposals=${finalProposals.size} | " +
                        "detections=${finalDetections.size} | " +
                        "detectionTop=$detectionTopStr | " +
                        "box=($boxStr) | " +
                        "scaleBoxW=$scaleBoxWidthStr | " +
                        "scaleBoxH=$scaleBoxHeightStr | " +
                        "scaleWRatio=$scaleWidthRatioStr | " +
                        "scaleHRatio=$scaleHeightRatioStr | " +
                        "scaleAreaRatio=$scaleAreaRatioStr | " +
                        "contentCandidates=${contentProposals.size} | " +
                        "contentTop=$contentTopStr | " +
                        "contentBox=($contentBoxStr)"
                )

                // Condition evaluation for PNG saving
                val currentHasDetections = finalDetections.isNotEmpty()
                val conditionA = (frameId % 10L == 0L)
                val conditionB = (prevRawMax < 0.10f && rawTop >= 0.10f)
                val conditionC = (prevRawMax < 0.30f && rawTop >= 0.30f)
                val conditionD = (prevRawMax >= 0.10f && rawTop < 0.10f)
                val conditionE = (prevRawMax >= 0.30f && rawTop < 0.30f)
                val conditionF = (!prevHasDetections && currentHasDetections) || (prevHasDetections && !currentHasDetections)

                // D1-L8M:
                // During very dark scenes, capture repeated YOLO phone
                // detections densely enough for false-positive replay.
                val conditionG =
                    brightness < 50.0 &&
                    currentHasDetections &&
                    (frameId % 3L == 0L)

                prevRawMax = rawTop
                prevHasDetections = currentHasDetections

                if (conditionA || conditionB || conditionC || conditionD || conditionE || conditionF || conditionG) {
                    runSavedPngCount++
                    val inputCopy = input.clone()
                    val targetDir = diagnosticFilesDir
                    diagnosticScope.launch {
                        try {
                            val argbPixels = IntArray(INPUT_SIZE * INPUT_SIZE)
                            for (y in 0 until INPUT_SIZE) {
                                for (x in 0 until INPUT_SIZE) {
                                    val idx = y * INPUT_SIZE + x
                                    val r = (inputCopy[idx].coerceIn(0f, 1f) * 255f).toInt()
                                    val g = (inputCopy[planeSize + idx].coerceIn(0f, 1f) * 255f).toInt()
                                    val b = (inputCopy[(planeSize * 2) + idx].coerceIn(0f, 1f) * 255f).toInt()
                                    argbPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                            val inputBitmap = Bitmap.createBitmap(argbPixels, INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
                            targetDir?.let { dir ->
                                val file = File(dir, "d1_l8c_frame_$frameIdStr.png")
                                FileOutputStream(file).use { fos ->
                                    inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                }
                                Log.d("GL_YOLO_INPUT_CAPTURE", "D1-L8C | saved input to ${file.absolutePath}")
                            }
                            inputBitmap.recycle()
                        } catch (e: Exception) {
                            Log.e("GL_YOLO_INPUT_CAPTURE", "D1-L8C | failed to save PNG", e)
                        }
                    }
                }
            }

            return YoloFrameResult(
                detections = finalDetections,
                proposals = finalProposals
            )

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
