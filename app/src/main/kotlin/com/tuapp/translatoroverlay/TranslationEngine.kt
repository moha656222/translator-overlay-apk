package com.tuapp.translatoroverlay

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

object TranslationEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val langIdentifier = LanguageIdentification.getClient()
    private var activeTranslator: Translator? = null
    private var currentSource: String? = null
    private var currentTarget: String? = null

    suspend fun processFrame(
        bitmap: Bitmap,
        targetLang: String = TranslateLanguage.SPANISH
    ): String? {
        return try {
            // 1. OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            val fullText = visionText.text.trim()

            if (fullText.isEmpty()) return null

            // 2. Identificación de idioma
            val sourceLang = langIdentifier.identifyLanguage(fullText).await()
            val mlSource = if (sourceLang == "und") TranslateLanguage.ENGLISH
                          else TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH

            // No traducir si ya está en el idioma destino
            if (mlSource == targetLang) return fullText

            // 3. Reusar o crear Translator
            if (activeTranslator == null || currentSource != mlSource || currentTarget != targetLang) {
                activeTranslator?.close()
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlSource)
                    .setTargetLanguage(targetLang)
                    .build()
                activeTranslator = Translation.getClient(options)
                currentSource = mlSource
                currentTarget = targetLang
            }

            // 4. Descargar modelo si hace falta y traducir
            activeTranslator?.downloadModelIfNeeded()?.await()
            activeTranslator?.translate(fullText)?.await()

        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        recognizer.close()
        langIdentifier.close()
        activeTranslator?.close()
    }
}
