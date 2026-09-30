package com.tom.pharmascan.scan

/** Variante F-Droid : zxing-cpp, 100 % logiciel libre. */
object ScannerFactory {
    fun create(autoZoom: Boolean, zoom: ZoomHooks): FrameScanner = ZxingFrameScanner(autoZoom, zoom)
}
