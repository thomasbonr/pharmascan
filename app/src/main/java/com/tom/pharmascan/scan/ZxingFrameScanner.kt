package com.tom.pharmascan.scan

import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scanner zxing-cpp (Apache 2.0), restreint au format DATA_MATRIX : la
 * détection est plus rapide et le code EAN imprimé à côté sur la boîte ne
 * produit aucun faux positif.
 *
 * Les lectures sont tentées en cascade, de la moins chère à la plus
 * robuste ([Attempt]). La première, utilisée à chaque image, suffit dans le
 * cas courant ; les suivantes ne sont tentées que lorsque le scan peine
 * depuis un moment (`tryHarder` côté appelant), pour ne pas ralentir le flux.
 *
 * zxing n'a pas d'auto-zoom : on le reconstruit à partir de la position du
 * code dans l'image (voir [suggestZoom]).
 */
internal class ZxingFrameScanner(
    private val autoZoom: Boolean,
    private val zoom: ZoomHooks,
) : FrameScanner {

    /** Une configuration de lecture. */
    private class Attempt(
        val label: String,
        val binarizer: BarcodeReader.Binarizer,
        val invert: Boolean = false,
        val denoise: Boolean = false,
    )

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX),
            // Codes minuscules ou peu contrastés sur boîte : on accepte de
            // payer quelques ms de plus par image.
            tryHarder = true,
            maxNumberOfSymbols = 1,
            // PLAIN : texte brut, où le FNC1 des DataMatrix GS1 est rendu par
            // le caractère GS (0x1D) attendu par Gs1Parser. Le mode par défaut
            // (HRI) produirait "(01)…(10)…", un format que le parseur ignore.
            textMode = BarcodeReader.TextMode.PLAIN,
        )
    )

    @Volatile override var lastReadSummary: String? = null
        private set

    private var lastZoomTimestamp = 0L

    override fun analyze(
        proxy: ImageProxy,
        tryHarder: Boolean,
        onHit: (raw: String, inverted: Boolean) -> Unit,
    ) {
        try {
            val attempts = if (tryHarder) FULL_CASCADE else FAST
            for (attempt in attempts) {
                val started = SystemClock.elapsedRealtime()
                val result = read(proxy, attempt) ?: continue
                lastReadSummary = "${attempt.label} · ${SystemClock.elapsedRealtime() - started} ms"

                if (autoZoom) {
                    val shortSide = min(proxy.cropRect.width(), proxy.cropRect.height())
                    suggestZoom(result.position, shortSide)
                }
                onHit(result.text!!, attempt.invert)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Analyse échouée", e)
        } finally {
            proxy.close()
        }
    }

    private fun read(proxy: ImageProxy, attempt: Attempt): BarcodeReader.Result? {
        reader.options.binarizer = attempt.binarizer
        reader.options.tryInvert = attempt.invert
        reader.options.tryDenoise = attempt.denoise
        return reader.read(proxy).firstOrNull { it.error == null && !it.text.isNullOrEmpty() }
    }

    override fun close() = Unit

    /**
     * Si le code occupe une petite part du cadre, zoome pour qu'il en occupe
     * environ [TARGET_FILL]. Espacé de [ZOOM_INTERVAL_MS] pour laisser
     * l'autofocus se réajuster entre deux corrections.
     */
    private fun suggestZoom(pos: BarcodeReader.Position, shortSide: Int) {
        if (shortSide <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastZoomTimestamp < ZOOM_INTERVAL_MS) return

        val size = max(dist(pos.topLeft, pos.topRight), dist(pos.topRight, pos.bottomRight))
        val fill = size / shortSide
        if (fill <= 0f || fill >= MIN_FILL) return

        val current = zoom.currentZoomRatio()
        // Facteur borné : un saut trop brutal fait perdre le code de vue.
        val factor = (TARGET_FILL / fill).coerceAtMost(MAX_STEP)
        val target = (current * factor).coerceIn(1f, zoom.maxZoomRatio.coerceAtLeast(1f))
        if (target - current < 0.05f) return

        lastZoomTimestamp = now
        zoom.applyZoom(target)
    }

    private fun dist(a: Point, b: Point): Float =
        hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())

    private companion object {
        const val TAG = "ZxingFrameScanner"

        /**
         * Lecture ordinaire : binarisation locale, qui encaisse un éclairage
         * inégal (reflets sur blister ou boîte vernie).
         */
        private val NORMAL = Attempt("normal", BarcodeReader.Binarizer.LOCAL_AVERAGE)

        val FAST = listOf(NORMAL)

        /**
         * Quand le scan peine, on élargit : binarisation globale (meilleure
         * sur un code très petit et uniformément éclairé), puis polarité
         * inversée (code clair sur fond sombre, fréquent sur les emballages
         * noirs), puis débruitage (image grainée en basse lumière). Chaque
         * étape double à peu près le coût de l'image.
         */
        val FULL_CASCADE = listOf(
            NORMAL,
            Attempt("histogramme global", BarcodeReader.Binarizer.GLOBAL_HISTOGRAM),
            Attempt("inversé", BarcodeReader.Binarizer.LOCAL_AVERAGE, invert = true),
            Attempt("débruité", BarcodeReader.Binarizer.LOCAL_AVERAGE, denoise = true),
            Attempt("inversé + débruité", BarcodeReader.Binarizer.LOCAL_AVERAGE, invert = true, denoise = true),
        )

        /** En dessous de cette part du petit côté du cadre, on zoome. */
        const val MIN_FILL = 0.18f
        /** Part visée du petit côté du cadre après zoom. */
        const val TARGET_FILL = 0.35f
        /** Facteur de zoom maximal appliqué en une seule correction. */
        const val MAX_STEP = 2f
        const val ZOOM_INTERVAL_MS = 800L
    }
}
