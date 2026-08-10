package com.tom.pharmascan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.tom.pharmascan.ui.ManualEntryDialog
import com.tom.pharmascan.ui.PharmaScanTheme
import com.tom.pharmascan.ui.ScanUiState
import com.tom.pharmascan.ui.ScannerScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PharmaScan — écran de scan.
 *
 * ------------------------------------------------------------------------
 * STRATÉGIE DE MISE AU POINT
 * ------------------------------------------------------------------------
 * Un DataMatrix pharmaceutique fait 5 à 8 mm de côté pour ~50 modules par
 * ligne. ML Kit exige au minimum 2 pixels par module, soit ~100 px nets sur
 * le code. En résolution d'analyse par défaut (640x480) et à bout de bras,
 * on en obtient 30 à 40, flous : le scan ne marche jamais.
 *
 * Cinq mesures combinées :
 *
 *  1. RÉSOLUTION D'ANALYSE relevée à 1920x1080 via ResolutionSelector.
 *  2. AUTOFOCUS CONTINU forcé (Camera2Interop, CONTROL_AF_MODE_CONTINUOUS_
 *     PICTURE) : beaucoup d'appareils utilisent sinon un AF paresseux qui ne
 *     re-converge pas quand on approche l'objet.
 *  3. AUTO-ZOOM ML KIT (ZoomSuggestionOptions) : quand un code est repéré
 *     mais illisible, la bibliothèque calcule elle-même le facteur de zoom
 *     nécessaire et nous le demande. C'est le mécanisme le plus efficace.
 *  4. RELANCE PÉRIODIQUE DE L'AF si rien n'est décodé pendant 2 s.
 *  5. TAP-TO-FOCUS et PINCH-TO-ZOOM, avec auto-annulation à 3 s pour
 *     repasser en AF continu.
 *
 * S'y ajoute la CONFIRMATION MULTI-FRAMES recommandée par Google : deux
 * lectures identiques consécutives exigées avant validation.
 *
 * ORDRE D'INITIALISATION — attention : le scanner ML Kit doit être construit
 * APRÈS le binding de la caméra, car ZoomSuggestionOptions a besoin du
 * facteur de zoom maximal réel de l'appareil. Le construire avant plafonne
 * l'auto-zoom à 1.0x et le rend totalement inopérant.
 */
@ExperimentalGetImage
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: MedicamentApi
    private lateinit var ha: HomeAssistant

    private val state = ScanUiState()

    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var scanner: BarcodeScanner? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var workExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private var maxZoomRatio = 1f
    private var lastRawValue: String? = null
    private var consecutiveCount = 0
    @Volatile private var processing = false
    private var lastDecodeTimestamp = 0L
    private var lastLockOnTimestamp = 0L

    private var showManualEntry by mutableStateOf(false)
    private var focusPoint by mutableStateOf<Offset?>(null)

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                previewView?.let { bindCamera(it) }
            } else {
                state.set(
                    ScanUiState.Phase.ERROR,
                    "Caméra refusée",
                    "La saisie manuelle du CIP13 reste disponible."
                )
            }
        }

    // ======================================================================
    // Cycle de vie
    // ======================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = MedicamentApi(prefs)
        ha = HomeAssistant(prefs)
        cameraExecutor = Executors.newSingleThreadExecutor()
        workExecutor = Executors.newFixedThreadPool(2)

        // On vide souvent une armoire entière : l'écran ne doit pas s'éteindre
        // entre deux boîtes.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Dessin sous les barres système : la preview caméra occupe tout
        // l'écran, et Compose gère les marges via statusBarsPadding().
        enableEdgeToEdge()

        setContent {
            // Thème sombre forcé : une interface claire derrière une preview
            // caméra crée des halos qui gênent la visée.
            PharmaScanTheme(forceDark = true) {
                ScannerScreen(
                    state = state,
                    focusPoint = focusPoint,
                    cameraPreview = { modifier ->
                        AndroidView(
                            modifier = modifier,
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    previewView = this
                                    if (hasCameraPermission()) bindCamera(this)
                                    else requestCameraPermission.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                    },
                    onTapFocus = { offset ->
                        focusPoint = offset
                        focusAt(offset.x, offset.y)
                        mainHandler.postDelayed({ focusPoint = null }, 900)
                    },
                    onZoom = { factor -> applyRelativeZoom(factor) },
                    onToggleTorch = { toggleTorch() },
                    onManualEntry = { showManualEntry = true },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onRetryQueue = { flushQueue(announceEmpty = true) }
                )

                if (showManualEntry) {
                    ManualEntryDialog(
                        onDismiss = { showManualEntry = false },
                        onSubmit = { cip, expiryIso ->
                            showManualEntry = false
                            submitManual(cip, expiryIso)
                        }
                    )
                }
            }
        }

        if (!prefs.isConfigured) {
            state.set(
                ScanUiState.Phase.ERROR,
                "Home Assistant non configuré",
                "Ouvre les réglages pour saisir l'URL, le jeton et l'entité."
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQueueBadge()
        flushQueue(announceEmpty = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        workExecutor.shutdown()
        scanner?.close()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ======================================================================
    // Caméra
    // ======================================================================

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(view: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                state.set(ScanUiState.Phase.ERROR, "Caméra indisponible", e.message)
                return@addListener
            }

            // --- Preview, autofocus continu forcé -------------------------
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }

            // --- Analyse haute résolution ---------------------------------
            // FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER : on obtient au moins
            // 1080p si l'appareil le propose, sinon la résolution disponible
            // la plus proche, sans planter sur un capteur exotique.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Binding caméra échoué", e)
                state.set(ScanUiState.Phase.ERROR, "Impossible d'ouvrir la caméra", e.message)
                return@addListener
            }

            // ORDRE CRITIQUE : maxZoomRatio n'est connu qu'une fois la caméra
            // liée. Le scanner ML Kit doit donc être construit ICI et pas
            // avant, sinon l'auto-zoom est plafonné à 1.0x et ne sert à rien.
            maxZoomRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            scanner?.close()
            scanner = buildScanner()

            analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }

            state.set(ScanUiState.Phase.READY, "Prêt à scanner")
            mainHandler.post(refocusLoop)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Scanner restreint au seul format DATA_MATRIX : cela accélère nettement
     * la détection et supprime les faux positifs sur le code-barres EAN
     * imprimé juste à côté sur la boîte.
     */
    private fun buildScanner(): BarcodeScanner {
        val zoomCallback = ZoomSuggestionOptions.ZoomCallback { ratio ->
            val cam = camera ?: return@ZoomCallback false
            cam.cameraControl.setZoomRatio(ratio.coerceIn(1f, maxZoomRatio))
            lastLockOnTimestamp = System.currentTimeMillis()
            mainHandler.post {
                state.lockingOn = true
                state.zoomRatio = ratio
            }
            true
        }

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder(zoomCallback)
                    .setMaxSupportedZoomRatio(maxZoomRatio.coerceAtLeast(1f))
                    .build()
            )
            .build()
        return BarcodeScanning.getClient(options)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val currentScanner = scanner
        val mediaImage = imageProxy.image
        if (currentScanner == null || mediaImage == null || processing) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        currentScanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let { onRawValue(it) }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Analyse échouée", e) }
            .addOnCompleteListener { imageProxy.close() }
    }

    /**
     * Confirmation multi-frames : un décodage sur image floue peut produire un
     * résultat différent d'une frame à l'autre. On exige deux lectures
     * identiques consécutives avant de valider.
     */
    private fun onRawValue(raw: String) {
        lastDecodeTimestamp = System.currentTimeMillis()
        if (raw == lastRawValue) consecutiveCount++ else {
            lastRawValue = raw
            consecutiveCount = 1
        }
        if (consecutiveCount < REQUIRED_CONSECUTIVE_READS) return

        consecutiveCount = 0
        lastRawValue = null
        processing = true
        mainHandler.post { state.lockingOn = false }
        handleScan(raw)
    }

    /**
     * Relance périodique de l'AF. Sans elle, certains capteurs restent
     * verrouillés sur l'arrière-plan pendant qu'on approche la boîte.
     * Cette boucle éteint aussi l'indicateur d'auto-zoom, qui resterait sinon
     * allumé indéfiniment après un code aperçu puis sorti du champ.
     */
    private val refocusLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (state.lockingOn && now - lastLockOnTimestamp > 1500) {
                state.lockingOn = false
            }
            if (!processing && now - lastDecodeTimestamp > 2000) {
                previewView?.let { focusAt(it.width / 2f, it.height / 2f) }
            }
            mainHandler.postDelayed(this, 1200)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val view = previewView ?: return
        try {
            val point = view.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                // Auto-annulation : sans elle, la caméra reste verrouillée sur
                // un plan devenu obsolète.
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Mise au point impossible", e)
        }
    }

    private fun applyRelativeZoom(factor: Float) {
        val cam = camera ?: return
        val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val target = (current * factor).coerceIn(1f, maxZoomRatio)
        cam.cameraControl.setZoomRatio(target)
        state.zoomRatio = target
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            state.set(state.phase, state.headline, "Pas de flash sur cet appareil")
            return
        }
        state.torchOn = !state.torchOn
        cam.cameraControl.enableTorch(state.torchOn)
    }

    // ======================================================================
    // Traitement d'un scan
    // ======================================================================

    private fun handleScan(raw: String) {
        val data = Gs1Parser.parse(raw)

        if (!data.isUsable) {
            feedback(success = false)
            state.set(
                ScanUiState.Phase.REJECTED,
                "Code non exploitable",
                data.rejectionReason ?: "format non reconnu"
            )
            releaseAfterDelay()
            return
        }

        val cip13 = data.cip13!!
        val boxKey = Gs1Parser.boxKey(data)

        if (prefs.isAlreadyScanned(boxKey)) {
            feedback(success = false)
            state.set(
                ScanUiState.Phase.REJECTED,
                "Boîte déjà enregistrée",
                "CIP $cip13 · lot ${data.lot ?: "non précisé"}"
            )
            releaseAfterDelay()
            return
        }

        feedback(success = true)
        state.set(
            ScanUiState.Phase.PROCESSING,
            "Recherche du médicament...",
            "CIP $cip13 · périme le ${data.expiryIso?.let { frenchDate(it) } ?: "?"}"
        )

        workExecutor.execute {
            val lookup = api.lookupBlocking(cip13)
            val name = lookup.name ?: "Médicament CIP $cip13"

            val label = buildString {
                append(name)
                data.expiryIso?.let { append(" — périme le ${frenchDate(it)}") }
            }
            val description = buildString {
                append("CIP13 : $cip13")
                data.lot?.let { append("\nLot : $it") }
                data.serial?.let { append("\nSérie : $it") }
                data.notices.forEach { append("\n[!] $it") }
                if (lookup.error != null) append("\n(nom non résolu : ${lookup.error})")
            }

            val result = ha.sendBlocking(HomeAssistant.Item(label, data.expiryIso, description))

            mainHandler.post {
                val entryStatus = when (result) {
                    is HomeAssistant.SendResult.Success -> {
                        prefs.rememberScanned(boxKey)
                        state.set(
                            ScanUiState.Phase.SUCCESS, name,
                            data.expiryIso?.let { "Périme le ${frenchDate(it)}" }
                        )
                        ScanUiState.EntryStatus.SENT
                    }
                    is HomeAssistant.SendResult.Queued -> {
                        prefs.rememberScanned(boxKey)
                        state.set(
                            ScanUiState.Phase.SUCCESS, name,
                            "Hors ligne — en file (${result.queueSize}), envoi automatique plus tard"
                        )
                        ScanUiState.EntryStatus.QUEUED
                    }
                    is HomeAssistant.SendResult.Failed -> {
                        state.set(ScanUiState.Phase.ERROR, "Échec de l'envoi", result.reason)
                        ScanUiState.EntryStatus.REJECTED
                    }
                }
                state.addEntry(
                    ScanUiState.ScanEntry(
                        name = name,
                        expiry = data.expiryIso?.let { frenchDate(it) },
                        cip13 = cip13,
                        status = entryStatus
                    )
                )
                refreshQueueBadge()
                releaseAfterDelay()
            }
        }
    }

    private fun releaseAfterDelay() {
        mainHandler.postDelayed({
            processing = false
            if (state.phase != ScanUiState.Phase.ERROR) {
                state.set(ScanUiState.Phase.READY, "Prêt pour la boîte suivante")
            }
        }, RESCAN_COOLDOWN_MS)
    }

    private fun submitManual(cip13: String, expiryIso: String?) {
        state.set(ScanUiState.Phase.PROCESSING, "Saisie manuelle", "CIP $cip13")
        workExecutor.execute {
            val lookup = api.lookupBlocking(cip13)
            val name = lookup.name ?: "Médicament CIP $cip13"
            val label = buildString {
                append(name)
                expiryIso?.let { append(" — périme le ${frenchDate(it)}") }
            }
            val result = ha.sendBlocking(
                HomeAssistant.Item(label, expiryIso, "CIP13 : $cip13 (saisie manuelle)")
            )
            mainHandler.post {
                val entryStatus = when (result) {
                    is HomeAssistant.SendResult.Success -> {
                        state.set(ScanUiState.Phase.SUCCESS, name, "Ajouté à Home Assistant")
                        ScanUiState.EntryStatus.SENT
                    }
                    is HomeAssistant.SendResult.Queued -> {
                        state.set(ScanUiState.Phase.SUCCESS, name, "En file (${result.queueSize})")
                        ScanUiState.EntryStatus.QUEUED
                    }
                    is HomeAssistant.SendResult.Failed -> {
                        state.set(ScanUiState.Phase.ERROR, "Échec", result.reason)
                        ScanUiState.EntryStatus.REJECTED
                    }
                }
                state.addEntry(
                    ScanUiState.ScanEntry(
                        name, expiryIso?.let { frenchDate(it) }, cip13, entryStatus
                    )
                )
                refreshQueueBadge()
                releaseAfterDelay()
            }
        }
    }

    // ======================================================================
    // File d'attente et retours utilisateur
    // ======================================================================

    private fun flushQueue(announceEmpty: Boolean) {
        workExecutor.execute {
            val before = ha.queueSize()
            if (before == 0) {
                if (announceEmpty) mainHandler.post {
                    state.set(state.phase, state.headline, "Rien en attente, tout est synchronisé")
                }
                return@execute
            }
            val sent = ha.flushQueueBlocking()
            mainHandler.post {
                state.set(
                    if (sent > 0) ScanUiState.Phase.SUCCESS else ScanUiState.Phase.REJECTED,
                    if (sent > 0) "$sent élément(s) synchronisé(s)" else "Home Assistant injoignable",
                    if (sent > 0) null else "$before élément(s) toujours en attente"
                )
                refreshQueueBadge()
            }
        }
    }

    private fun refreshQueueBadge() {
        workExecutor.execute {
            val size = ha.queueSize()
            mainHandler.post { state.pendingCount = size }
        }
    }

    private fun feedback(success: Boolean) {
        vibrate(if (success) 35 else 110)
        if (!prefs.soundEnabled) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(
                if (success) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 140
            )
            mainHandler.postDelayed({ tone.release() }, 300)
        } catch (e: Exception) {
            Log.w(TAG, "Bip impossible", e)
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(ms)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration impossible", e)
        }
    }

    private fun frenchDate(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
    }

    companion object {
        private const val TAG = "PharmaScan"
        /** Lectures identiques consécutives exigées avant validation. */
        private const val REQUIRED_CONSECUTIVE_READS = 2
        /** Pause après un scan traité, pour éviter de relire la même boîte. */
        private const val RESCAN_COOLDOWN_MS = 1600L
    }
}
