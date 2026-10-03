package com.tom.pharmascan.scan

import androidx.camera.core.ImageProxy

/**
 * Lecteur de DataMatrix appliqué aux images de la caméra.
 *
 * Seule implémentation : [ZxingFrameScanner] (zxing-cpp, Apache 2.0). L'appli
 * n'embarque aucune bibliothèque propriétaire.
 */
interface FrameScanner : AutoCloseable {
    /** Résumé de la dernière lecture réussie (stratégie, durée), pour le diagnostic. */
    val lastReadSummary: String?

    /**
     * Analyse une image. L'implémentation prend possession de [proxy] et DOIT
     * le fermer, quel que soit le résultat.
     *
     * @param tryHarder tenter aussi les lectures de secours (polarité inversée,
     *   autre binarisation, débruitage). Plus coûteux, donc demandé seulement
     *   quand la lecture normale peine depuis un moment.
     * @param onHit appelé (depuis n'importe quel thread) avec le contenu brut
     *   du code et un indicateur « lu en polarité inversée ».
     */
    fun analyze(proxy: ImageProxy, tryHarder: Boolean, onHit: (raw: String, inverted: Boolean) -> Unit)
}

/** Ce dont un scanner a besoin pour piloter le zoom de la caméra. */
class ZoomHooks(
    /** Zoom maximal réel de l'appareil (connu seulement une fois la caméra liée). */
    val maxZoomRatio: Float,
    /** Zoom courant de la caméra. */
    val currentZoomRatio: () -> Float,
    /** Applique un zoom ABSOLU. Renvoie false si la suggestion n'a pas été prise en compte. */
    val applyZoom: (Float) -> Boolean,
)
