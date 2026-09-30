package com.tom.pharmascan.scan

import android.media.Image
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Scanner ML Kit restreint au seul format DATA_MATRIX : cela accélère nettement
 * la détection et supprime les faux positifs sur le code-barres EAN imprimé
 * juste à côté sur la boîte.
 *
 * Le zoom automatique est optionnel (réglage utilisateur) : il ne corrige pas
 * un problème de mise au point, seulement un code trop petit dans le cadre.
 */
internal class MlKitFrameScanner(autoZoom: Boolean, zoom: ZoomHooks) : FrameScanner {

    private val scanner: BarcodeScanner

    /** Tampons réutilisés pour l'inversion, pour ne pas allouer à chaque image. */
    private var invertedFrame: ByteArray? = null
    private var invertedRow: ByteArray? = null

    init {
        val builder = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)

        if (autoZoom) {
            val callback = ZoomSuggestionOptions.ZoomCallback { ratio ->
                zoom.applyZoom(ratio.coerceIn(1f, zoom.maxZoomRatio))
            }
            builder.setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder(callback)
                    .setMaxSupportedZoomRatio(zoom.maxZoomRatio.coerceAtLeast(1f))
                    .build()
            )
        }
        scanner = BarcodeScanning.getClient(builder.build())
    }

    override fun analyze(
        proxy: ImageProxy,
        tryInverted: Boolean,
        onHit: (raw: String, inverted: Boolean) -> Unit,
    ) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees

        // Seconde chance pour les codes à polarité inversée (clair sur fond
        // sombre), fréquents sur les emballages noirs. Doit être construite
        // AVANT la fermeture de l'ImageProxy.
        val invertedImage = if (tryInverted) buildInvertedImage(mediaImage, rotation) else null

        scanner.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                if (raw != null) {
                    onHit(raw, false)
                } else if (invertedImage != null) {
                    // L'image inversée est une copie indépendante : elle reste
                    // valide après la fermeture de l'ImageProxy.
                    scanner.process(invertedImage)
                        .addOnSuccessListener { inv ->
                            inv.firstNotNullOfOrNull { it.rawValue }?.let { onHit(it, true) }
                        }
                        .addOnFailureListener { e -> Log.w(TAG, "Analyse inversée échouée", e) }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Analyse échouée", e) }
            .addOnCompleteListener { proxy.close() }
    }

    override fun close() = scanner.close()

    /**
     * Construit une image en niveaux de gris inversée (255 - luminance) à
     * partir du plan Y de la frame.
     *
     * Un DataMatrix pharmaceutique est normalement imprimé sombre sur fond
     * clair. Sur les emballages noirs il est imprimé en clair sur fond
     * sombre : le code est parfaitement net, mais ML Kit ne le décode pas,
     * ses binariseurs supposant la polarité standard. Lui fournir la version
     * inversée résout le cas sans rien changer au reste du pipeline.
     *
     * Seul le plan de luminance porte l'information utile à un lecteur de
     * code : la chrominance est donc remplie de 128 (neutre), ce qui produit
     * une image NV21 valide en niveaux de gris.
     */
    private fun buildInvertedImage(mediaImage: Image, rotation: Int): InputImage? {
        return try {
            val plane = mediaImage.planes[0]
            val width = mediaImage.width
            val height = mediaImage.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val luma = plane.buffer

            val ySize = width * height
            val needed = ySize + ySize / 2
            val out = invertedFrame?.takeIf { it.size == needed }
                ?: ByteArray(needed).also { invertedFrame = it }
            val row = invertedRow?.takeIf { it.size >= rowStride }
                ?: ByteArray(rowStride).also { invertedRow = it }

            var offset = 0
            for (y in 0 until height) {
                // Cast explicite : ByteBuffer.position(int) a un type de
                // retour covariant depuis Java 9, ce qui rend l'appel
                // ambigu à la compilation selon le JDK utilisé.
                (luma as java.nio.Buffer).position(y * rowStride)
                luma.get(row, 0, minOf(rowStride, luma.remaining()))
                for (x in 0 until width) {
                    out[offset++] = (255 - (row[x * pixelStride].toInt() and 0xFF)).toByte()
                }
            }
            java.util.Arrays.fill(out, ySize, needed, NEUTRAL_CHROMA)

            InputImage.fromByteArray(out.copyOf(), width, height, rotation, InputImage.IMAGE_FORMAT_NV21)
        } catch (e: Exception) {
            Log.w(TAG, "Inversion de l'image impossible", e)
            null
        }
    }

    private companion object {
        const val TAG = "MlKitFrameScanner"
        /** Chrominance neutre d'une image NV21 en niveaux de gris. */
        const val NEUTRAL_CHROMA: Byte = 128.toByte()
    }
}
