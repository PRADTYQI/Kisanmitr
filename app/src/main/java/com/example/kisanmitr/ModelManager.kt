package com.example.kisanmitr

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.ImageClassifier

class ModelManager(private val context: Context) {

    private var imageClassifier: ImageClassifier? = null

    fun classify(bitmap: Bitmap): List<String> {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))

        val results = imageClassifier?.classify(tensorImage)
        return results?.flatMap { classifications ->
            classifications.categories.map { category ->
                "${category.label} - ${category.score}"
            }
        } ?: emptyList()
    }

    fun loadModel(modelName: String) {
        close()
        try {
            val baseOptions = BaseOptions.builder().useGpu().build()
            val options = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(3)
                .build()
            imageClassifier = ImageClassifier.createFromFileAndOptions(
                context,
                modelName,
                options
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {
        imageClassifier?.close()
        imageClassifier = null
    }
}