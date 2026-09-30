package com.tom.pharmascan.scan

import android.graphics.Point
import android.util.Log
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scanner zxing-cpp (Apache 2.0), restreint au format DATA_MATRIX.
 *
 * Différences avec ML Kit, compensées ici :
 *  - polarité inversée : gérée nativement par `tryInvert`, sans copier
 *    l'image. Elle double le coût de la lecture, donc n'est tentée que sur
 *    demande, et seulement après l'échec de la lecture normale ;
 *  - auto-zoom : zxing n'en a pas. On le reconstruit à partir de la position
 *    du code dans l'image (voir [suggestZoom]).
 */
internal class ZxingFrameScanner(
    private val autoZoom: Boolean,
    private val zoom: ZoomHooks,
) : FrameScanner {

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

    private var lastZoomTimestamp = 0L

    override fun analyze(
        proxy: ImageProxy,
        tryInverted: Boolean,
        onHit: (raw: String, inverted: Boolean) -> Unit,
    ) {
        try {
            var inverted = false
            var result = readFirst(proxy, invert = false)
            if (result == null && tryInverted) {
                // La lecture normale a échoué : si celle-ci réussit, c'est bien
                // la polarité inversée qui a servi.
                result = readFirst(proxy, invert = true)
                inverted = result != null
            }
            if (result == null) return

            if (autoZoom) {
                val shortSide = min(proxy.cropRect.width(), proxy.cropRect.height())
                suggestZoom(result.position, shortSide)
            }
            onHit(result.text!!, inverted)
        } catch (e: Exception) {
            Log.w(TAG, "Analyse échouée", e)
        } finally {
            proxy.close()
        }
    }

    private fun readFirst(proxy: ImageProxy, invert: Boolean): BarcodeReader.Result? {
        reader.options.tryInvert = invert
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
        /** En dessous de cette part du petit côté du cadre, on zoome. */
        const val MIN_FILL = 0.18f
        /** Part visée du petit côté du cadre après zoom. */
        const val TARGET_FILL = 0.35f
        /** Facteur de zoom maximal appliqué en une seule correction. */
        const val MAX_STEP = 2f
        const val ZOOM_INTERVAL_MS = 800L
    }
}
