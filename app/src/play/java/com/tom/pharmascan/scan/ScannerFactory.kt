package com.tom.pharmascan.scan

/** Variante Google Play : ML Kit. */
object ScannerFactory {
    fun create(autoZoom: Boolean, zoom: ZoomHooks): FrameScanner = MlKitFrameScanner(autoZoom, zoom)
}
