package com.guardianlab.app

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

object MobileClipLoadProbe {

    private const val TAG = "GL_MOBILECLIP_PROBE"

    private const val MODEL_ASSET =
        "D1_K7W_MOBILECLIP2_S0_VISUAL_FP32_EMBEDDED.onnx"

    private const val TENSOR_ASSET =
        "D1_K7R_IMG0862_FRAME227_RANK2_FP32.npy"

    private const val EXPECTED_FLOATS =
        1 * 3 * 256 * 256

    fun run(context: Context) {

        Log.i(TAG, "==================================================")
        Log.i(TAG, "GUARDIANLAB — D1-K7X")
        Log.i(TAG, "EXACT COLAB TENSOR → S9 FP32 MOBILECLIP")
        Log.i(TAG, "==================================================")

        val environment = OrtEnvironment.getEnvironment()

        var session: OrtSession? = null
        var tensor: OnnxTensor? = null

        try {

            // ========================================================
            // 1. LOAD NPY
            // ========================================================

            val npyBytes = context.assets
                .open(TENSOR_ASSET)
                .use { it.readBytes() }

            Log.i(
                TAG,
                "NPY asset bytes: ${npyBytes.size}"
            )

            // ========================================================
            // 2. VERIFY NPY MAGIC
            // ========================================================

            require(npyBytes.size >= 10) {
                "NPY file too small"
            }

            require(
                npyBytes[0] == 0x93.toByte() &&
                        npyBytes[1].toInt().toChar() == 'N' &&
                        npyBytes[2].toInt().toChar() == 'U' &&
                        npyBytes[3].toInt().toChar() == 'M' &&
                        npyBytes[4].toInt().toChar() == 'P' &&
                        npyBytes[5].toInt().toChar() == 'Y'
            ) {
                "Invalid NPY magic"
            }

            val major = npyBytes[6].toInt() and 0xFF
            val minor = npyBytes[7].toInt() and 0xFF

            Log.i(TAG, "NPY version: $major.$minor")

            // ========================================================
            // 3. READ HEADER LENGTH
            // ========================================================

            val headerLength: Int
            val headerStart: Int

            if (major == 1) {

                headerLength =
                    (npyBytes[8].toInt() and 0xFF) or
                            ((npyBytes[9].toInt() and 0xFF) shl 8)

                headerStart = 10

            } else {

                require(npyBytes.size >= 12) {
                    "NPY v2/v3 header truncated"
                }

                headerLength =
                    (npyBytes[8].toInt() and 0xFF) or
                            ((npyBytes[9].toInt() and 0xFF) shl 8) or
                            ((npyBytes[10].toInt() and 0xFF) shl 16) or
                            ((npyBytes[11].toInt() and 0xFF) shl 24)

                headerStart = 12
            }

            val dataStart =
                headerStart + headerLength

            require(dataStart <= npyBytes.size) {
                "NPY header exceeds file size"
            }

            val header = String(
                npyBytes,
                headerStart,
                headerLength,
                Charsets.US_ASCII
            ).trim()

            Log.i(TAG, "NPY header: $header")

            // ========================================================
            // 4. VERIFY FROZEN TENSOR CONTRACT
            // ========================================================

            require(
                header.contains("'descr': '<f4'") ||
                        header.contains("\"descr\": \"<f4\"")
            ) {
                "Expected little-endian float32 NPY"
            }

            require(
                header.contains("(1, 3, 256, 256)")
            ) {
                "Unexpected NPY tensor shape"
            }

            val payloadBytes =
                npyBytes.size - dataStart

            val expectedPayloadBytes =
                EXPECTED_FLOATS * 4

            Log.i(
                TAG,
                "NPY payload bytes: $payloadBytes"
            )

            require(
                payloadBytes == expectedPayloadBytes
            ) {
                "Unexpected payload size: " +
                        "$payloadBytes != $expectedPayloadBytes"
            }

            // ========================================================
            // 5. READ EXACT FLOAT32 PAYLOAD
            // ========================================================

            val payloadBuffer =
                ByteBuffer.wrap(
                    npyBytes,
                    dataStart,
                    payloadBytes
                )
                    .order(ByteOrder.LITTLE_ENDIAN)

            val inputData =
                FloatArray(EXPECTED_FLOATS)

            payloadBuffer
                .asFloatBuffer()
                .get(inputData)

            var inputNan = 0
            var inputInf = 0

            var inputMin =
                Float.POSITIVE_INFINITY

            var inputMax =
                Float.NEGATIVE_INFINITY

            var inputSum = 0.0

            for (v in inputData) {

                if (v.isNaN()) inputNan++
                if (v.isInfinite()) inputInf++

                if (v.isFinite()) {

                    if (v < inputMin) {
                        inputMin = v
                    }

                    if (v > inputMax) {
                        inputMax = v
                    }

                    inputSum += v.toDouble()
                }
            }

            val inputMean =
                inputSum / inputData.size

            Log.i(TAG, "----------------------------------------------")
            Log.i(TAG, "EXACT INPUT VALIDATION")
            Log.i(TAG, "----------------------------------------------")

            Log.i(
                TAG,
                "Float count : ${inputData.size}"
            )

            Log.i(
                TAG,
                "NaN count   : $inputNan"
            )

            Log.i(
                TAG,
                "Inf count   : $inputInf"
            )

            Log.i(
                TAG,
                "Input min   : %.9f".format(inputMin)
            )

            Log.i(
                TAG,
                "Input max   : %.9f".format(inputMax)
            )

            Log.i(
                TAG,
                "Input mean  : %.9f".format(inputMean)
            )

            // Colab K7R reference:
            // min  = 0.000000000
            // max  = 1.000000000
            // mean = 0.278248459

            require(inputNan == 0) {
                "Input contains NaNs"
            }

            require(inputInf == 0) {
                "Input contains infinities"
            }

            // ========================================================
            // 6. LOAD FP16 MOBILECLIP
            // ========================================================

            val modelBytes = context.assets
                .open(MODEL_ASSET)
                .use { it.readBytes() }

            Log.i(
                TAG,
                "Model bytes: ${modelBytes.size}"
            )

            val options =
                OrtSession.SessionOptions().apply {

                    // Controlled CPU baseline.
                    setIntraOpNumThreads(2)

                    setOptimizationLevel(
                        OrtSession.SessionOptions
                            .OptLevel.ALL_OPT
                    )
                }

            val loadStart =
                System.nanoTime()

            session = environment.createSession(
                modelBytes,
                options
            )

            val loadMs =
                (System.nanoTime() - loadStart) /
                        1_000_000.0

            Log.i(
                TAG,
                "ORT session load PASS | %.2f ms"
                    .format(loadMs)
            )

            // ========================================================
            // 7. CREATE EXACT [1,3,256,256] INPUT
            // ========================================================

            val shape = longArrayOf(
                1L,
                3L,
                256L,
                256L
            )

            tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                shape
            )

            Log.i(
                TAG,
                "Input tensor PASS | shape=[1,3,256,256]"
            )

            // ========================================================
            // 8. WARM-UP
            // ========================================================

            session.run(
                mapOf("image" to tensor)
            ).use {
                // Warm-up only.
            }

            Log.i(TAG, "Warm-up PASS")

            // ========================================================
            // 9. MEASURED INFERENCE
            // ========================================================

            val startNs =
                System.nanoTime()

            session.run(
                mapOf("image" to tensor)
            ).use { result ->

                val elapsedMs =
                    (System.nanoTime() - startNs) /
                            1_000_000.0

                @Suppress("UNCHECKED_CAST")
                val output =
                    result[0].value as Array<FloatArray>

                val embedding =
                    output[0]

                // D1-K7Y — deterministic fingerprint of all 512 FP32 values
                val digest = java.security.MessageDigest.getInstance("SHA-256")

                val embeddingBytes =
                    ByteBuffer
                        .allocate(embedding.size * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)

                for (v in embedding) {
                    embeddingBytes.putFloat(v)
                }

                val embeddingSha256 =
                    digest.digest(embeddingBytes.array())
                        .joinToString("") { "%02x".format(it) }

                Log.i(
                    TAG,
                    "D1-K7Y embedding SHA256: $embeddingSha256"
                )

                // Print all 512 values in manageable chunks for numerical comparison.
                val chunkSize = 32

                for (start in embedding.indices step chunkSize) {

                    val end =
                        minOf(start + chunkSize, embedding.size)

                    val values =
                        (start until end)
                            .joinToString(",") { index ->
                                "%.9g".format(
                                    java.util.Locale.US,
                                    embedding[index]
                                )
                            }

                    Log.i(
                        TAG,
                        "D1-K7Y VALUES[$start..${end - 1}]: $values"
                    )
                }

                var nanCount = 0
                var infCount = 0

                var sumSquares = 0.0

                var minValue =
                    Float.POSITIVE_INFINITY

                var maxValue =
                    Float.NEGATIVE_INFINITY

                for (v in embedding) {

                    if (v.isNaN()) {
                        nanCount++
                    }

                    if (v.isInfinite()) {
                        infCount++
                    }

                    if (v.isFinite()) {

                        sumSquares +=
                            v.toDouble() *
                                    v.toDouble()

                        if (v < minValue) {
                            minValue = v
                        }

                        if (v > maxValue) {
                            maxValue = v
                        }
                    }
                }

                val norm =
                    sqrt(sumSquares)

                Log.i(TAG, "----------------------------------------------")
                Log.i(TAG, "D1-K7X S9 RESULT")
                Log.i(TAG, "----------------------------------------------")

                Log.i(
                    TAG,
                    "Embedding length : ${embedding.size}"
                )

                Log.i(
                    TAG,
                    "NaN count        : $nanCount"
                )

                Log.i(
                    TAG,
                    "Inf count        : $infCount"
                )

                Log.i(
                    TAG,
                    "Embedding norm   : %.12f"
                        .format(norm)
                )

                Log.i(
                    TAG,
                    "Embedding min    : %.12f"
                        .format(minValue)
                )

                Log.i(
                    TAG,
                    "Embedding max    : %.12f"
                        .format(maxValue)
                )

                Log.i(
                    TAG,
                    "Inference time   : %.2f ms"
                        .format(elapsedMs)
                )

                val pass =
                    embedding.size == 512 &&
                            nanCount == 0 &&
                            infCount == 0 &&
                            norm.isFinite() &&
                            norm > 0.0

                if (pass) {

                    Log.i(
                        TAG,
                        "D1-K7X RESULT: PASS"
                    )

                } else {

                    Log.e(
                        TAG,
                        "D1-K7X RESULT: FAIL"
                    )
                }
            }

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "D1-K7X RESULT: FAIL",
                t
            )

        } finally {

            try {
                tensor?.close()
            } catch (_: Throwable) {
            }

            try {
                session?.close()
            } catch (_: Throwable) {
            }

            Log.i(TAG, "==================================================")
            Log.i(TAG, "D1-K7X PROBE COMPLETE")
            Log.i(TAG, "==================================================")
        }
    }
}