package com.tom.pharmascan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
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
import com.tom.pharmascan.diagnostic.DiagnosticSnapshot
import com.tom.pharmascan.diagnostic.render
import com.tom.pharmascan.scan.FrameScanner
import com.tom.pharmascan.scan.ZxingFrameScanner
import com.tom.pharmascan.scan.ZoomHooks
import com.tom.pharmascan.ui.ManualEntryDialog
import com.tom.pharmascan.ui.PharmaScanTheme
import com.tom.pharmascan.ui.ScanUiState
import com.tom.pharmascan.ui.ScannerScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * PharmaScan — écran de scan.
 *
 * ------------------------------------------------------------------------
 * STRATÉGIE DE MISE AU POINT
 * ------------------------------------------------------------------------
 * Un DataMatrix pharmaceutique fait 5 à 8 mm de côté pour ~50 modules par
 * ligne. Un décodeur exige au minimum 2 pixels par module, soit ~100 px nets sur
 * le code. En résolution d'analyse par défaut (640x480) et à bout de bras,
 * on en obtient 30 à 40, flous : le scan ne marche jamais.
 *
 * ------------------------------------------------------------------------
 * ERREUR HISTORIQUE À NE PAS REFAIRE : NE PAS "AIDER" L'AF CONTINU
 * ------------------------------------------------------------------------
 * Les premières versions relançaient `startFocusAndMetering()` toutes les
 * 1,2 s tant que rien n'était décodé, en croyant réveiller un AF paresseux.
 * C'était la CAUSE du problème, pas le remède :
 *
 *   - `startFocusAndMetering()` SORT du mode continu et lance une mise au
 *     point ponctuelle (AF_MODE_AUTO + trigger), qui reste verrouillée
 *     jusqu'à l'auto-annulation ;
 *   - un balayage AF complet demande souvent plus de 1,2 s ;
 *   - on interrompait donc chaque balayage avant sa convergence, pour en
 *     relancer un autre. L'objectif pompait indéfiniment sans jamais
 *     accrocher, et plus l'appli "insistait", moins elle faisait le point.
 *
 * Preuve par l'usage : l'appli photo native fait le point parfaitement sur
 * le même capteur. Le matériel n'a jamais été en cause — c'était nous.
 *
 * Règle : on laisse l'AF continu travailler. On n'émet une commande de mise
 * au point QUE sur action explicite de l'utilisateur (tap-to-focus). En
 * récupération, on appelle `cancelFocusAndMetering()` — qui REND la main à
 * l'AF continu — et surtout jamais un nouveau déclenchement.
 *
 * ------------------------------------------------------------------------
 * MESURES EFFECTIVEMENT APPLIQUÉES
 * ------------------------------------------------------------------------
 *  1. AUTOFOCUS CONTINU forcé (Camera2Interop, CONTROL_AF_MODE_CONTINUOUS_
 *     PICTURE), puis LAISSÉ TRANQUILLE (voir ci-dessus).
 *  2. SURVEILLANCE DE L'ÉTAT AF réel via un CaptureCallback de session : si
 *     l'AF se retrouve verrouillé (FOCUSED_LOCKED / NOT_FOCUSED_LOCKED,
 *     typiquement après un tap) alors que plus rien ne se décode, on rend
 *     la main à l'AF continu avec `cancelFocusAndMetering()`. Une seule
 *     commande, espacée, jamais en boucle serrée.
 *  3. CAPTEUR : on garde le capteur arrière par défaut, celui qu'utilise
 *     l'appli native. Un capteur "macro" peut être forcé dans les réglages
 *     pour les appareils dont l'objectif principal ne descend pas sous
 *     ~10 cm — mais uniquement parmi les capteurs qui déclarent un vrai AF
 *     (sur beaucoup de Samsung, l'ultra grand-angle est à FOCUS FIXE :
 *     le sélectionner supprimerait purement et simplement l'autofocus).
 *  4. RÉSOLUTION D'ANALYSE relevée à 1920x1080 via ResolutionSelector.
 *  5. AUTO-ZOOM (reconstruit à partir de la taille du code dans l'image),
 *     désactivable : compense un
 *     code trop petit dans le cadre, sans effet sur la mise au point.
 *  6. TAP-TO-FOCUS et PINCH-TO-ZOOM, avec auto-annulation courte pour
 *     repasser rapidement en AF continu.
 *
 * S'y ajoute la CONFIRMATION MULTI-FRAMES recommandée par Google : deux
 * lectures identiques consécutives exigées avant validation.
 *
 * ORDRE D'INITIALISATION — attention : le scanner doit être construit
 * APRÈS le binding de la caméra, car l'auto-zoom a besoin du
 * facteur de zoom maximal réel de l'appareil. Le construire avant plafonne
 * l'auto-zoom à 1.0x et le rend totalement inopérant.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: MedicamentApi
    private lateinit var ha: HomeAssistant

    private val state = ScanUiState()

    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var scanner: FrameScanner? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var workExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private var maxZoomRatio = 1f
    private var lastRawValue: String? = null
    private var consecutiveCount = 0
    @Volatile private var processing = false
    @Volatile private var lastDecodeTimestamp = 0L
    @Volatile private var lastLockOnTimestamp = 0L

    /** Dernier CONTROL_AF_STATE remonté par la session de capture (thread caméra). */
    @Volatile private var afState: Int? = null
    /** Distance de mise au point courante en dioptries, pour le diagnostic. */
    @Volatile private var lensFocusDistance: Float? = null
    private var lastAfRecovery = 0L
    private var activeCameraLabel = "?"
    private var lastInvertedTimestamp = 0L
    /** Réglage d'objectif utilisé lors du dernier binding, pour détecter un changement. */
    private var boundWithMacroLens = false

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
        ha.nameResolver = { cip13 -> api.lookupBlocking(cip13).name }
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
                    onOpenHistory = {
                        startActivity(Intent(this, HistoryActivity::class.java))
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

        if (prefs.haEnabled && !prefs.isConfigured) {
            state.set(
                ScanUiState.Phase.ERROR,
                "Home Assistant non configuré",
                when (prefs.connectionMode) {
                    ConnectionMode.WEBHOOK ->
                        "Ouvre les réglages pour saisir l'URL et l'identifiant du webhook."
                    ConnectionMode.TOKEN ->
                        "Ouvre les réglages pour saisir l'URL, le jeton et l'entité."
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQueueBadge()
        flushQueue(announceEmpty = false)

        if (camera != null) {
            // Reconstruit le scanner (pas la caméra) pour prendre en compte un
            // changement du réglage "zoom automatique".
            scanner?.close()
            scanner = buildScanner()

            // Le choix de l'objectif, lui, impose un rebinding complet.
            if (prefs.macroLensEnabled != boundWithMacroLens) {
                previewView?.let { bindCamera(it) }
            }

            // Reprend la surveillance suspendue dans onPause().
            mainHandler.removeCallbacks(afWatchdog)
            mainHandler.post(afWatchdog)
        }
        if (!prefs.afDiagnosticsEnabled) state.debugInfo = null
    }

    override fun onPause() {
        super.onPause()
        // La surveillance AF tournait toutes les 700 ms même écran quitté
        // (Réglages, Historique, appli en arrière-plan) : lecture du réglage
        // de diagnostic dans le magasin chiffré à chaque passage, et commandes
        // envoyées à une caméra que CameraX a déjà fermée. Elle n'a de sens
        // que caméra active.
        mainHandler.removeCallbacks(afWatchdog)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        workExecutor.shutdown()
        scanner?.close()
    }

    /**
     * Lance une tâche réseau/disque en arrière-plan, sauf si l'écran est déjà
     * détruit.
     *
     * Un envoi peut durer une vingtaine de secondes (API médicaments + HA +
     * vidange de la file). Si l'utilisateur quitte l'écran entre-temps,
     * onDestroy() arrête workExecutor, mais le callback posté en fin d'envoi
     * s'exécute quand même (il est posté APRÈS removeCallbacksAndMessages) et
     * appelait refreshQueueBadge() -> execute() sur un executor arrêté ->
     * RejectedExecutionException sur le thread principal, donc crash.
     */
    private fun runInBackground(task: () -> Unit) {
        if (workExecutor.isShutdown) return
        try {
            workExecutor.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Tâche ignorée : écran en cours de fermeture", e)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ======================================================================
    // Caméra
    // ======================================================================

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
            // Le CaptureCallback ne sert QU'À OBSERVER l'état réel de l'AF :
            // sans lui, on pilotait à l'aveugle et on "corrigeait" un AF qui
            // n'avait rien demandé. Il tourne sur un thread caméra, donc il
            // reste volontairement trivial (lecture de deux entiers).
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                // Certains constructeurs (Samsung inclus) activent des modes
                // scène qui prennent la main sur l'AF : on force le pilotage
                // automatique standard.
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_AUTO
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_DISABLED
                )
                .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        lensFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    }
                })
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
                    this, selectCamera(provider), preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Binding caméra échoué", e)
                state.set(ScanUiState.Phase.ERROR, "Impossible d'ouvrir la caméra", e.message)
                return@addListener
            }

            // ORDRE CRITIQUE : maxZoomRatio n'est connu qu'une fois la caméra
            // liée. Le scanner doit donc être construit ICI et pas
            // avant, sinon l'auto-zoom est plafonné à 1.0x et ne sert à rien.
            maxZoomRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            scanner?.close()
            scanner = buildScanner()

            analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }

            state.set(ScanUiState.Phase.READY, "Prêt à scanner")
            // Un rebinding (changement d'objectif) ne doit pas empiler une
            // seconde surveillance.
            mainHandler.removeCallbacks(afWatchdog)
            mainHandler.post(afWatchdog)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Par défaut : le capteur arrière standard, celui qu'utilise l'appli
     * photo native. C'est délibéré — quand l'appli native fait le point
     * correctement sur un appareil, ce capteur n'est pas le problème, et le
     * "sélectionner mieux" ne fait qu'introduire des régressions.
     *
     * Le mode macro (réglage explicite) cherche le capteur arrière capable
     * de faire le point le plus près, mais UNIQUEMENT parmi ceux qui
     * déclarent un autofocus réel :
     *
     *  - LENS_INFO_MINIMUM_FOCUS_DISTANCE en dioptries (1/m), 0 = focus FIXE.
     *    Sur beaucoup de Samsung (dont la série S), l'ultra grand-angle est à
     *    focus fixe : le retenir supprimerait tout autofocus.
     *  - CONTROL_AF_AVAILABLE_MODES doit contenir CONTINUOUS_PICTURE, sinon
     *    le mode continu qu'on force plus haut serait silencieusement ignoré.
     */
    private fun selectCamera(provider: ProcessCameraProvider): CameraSelector {
        boundWithMacroLens = prefs.macroLensEnabled
        if (!prefs.macroLensEnabled) {
            activeCameraLabel = "capteur par défaut"
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraInfos: List<CameraInfo> = try {
            CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
        } catch (e: Exception) {
            emptyList()
        }

        var bestId: String? = null
        var bestMinFocusDistance = 0f

        for (info in backCameraInfos) {
            val id = Camera2CameraInfo.from(info).cameraId
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val capabilities =
                    chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val backwardCompatible = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                )
                val afModes =
                    chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val hasContinuousAf =
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                // > 0 exclut les objectifs à focus fixe.
                val minFocusDistance =
                    chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                if (backwardCompatible && hasContinuousAf &&
                    minFocusDistance > bestMinFocusDistance
                ) {
                    bestMinFocusDistance = minFocusDistance
                    bestId = id
                }
            } catch (e: Exception) {
                Log.w(TAG, "Caractéristiques caméra $id illisibles", e)
            }
        }

        val chosenId = bestId
        if (chosenId == null) {
            Log.i(TAG, "Mode macro : aucun capteur AF plus proche, repli sur le capteur par défaut")
            activeCameraLabel = "défaut (pas de macro AF)"
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        val minFocusMm = (1000f / bestMinFocusDistance).toInt()
        Log.i(TAG, "Mode macro : capteur id=$chosenId, distance mini ~$minFocusMm mm")
        activeCameraLabel = "macro id=$chosenId (~$minFocusMm mm)"
        return CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == chosenId } }
            .build()
    }

    /**
     * Construit le lecteur zxing-cpp. Il ne lit que le format DATA_MATRIX, ce
     * qui accélère la détection et évite les faux positifs sur l'EAN imprimé
     * à côté.
     *
     * Le zoom automatique est optionnel (réglage utilisateur) : il ne corrige
     * pas un problème de mise au point, seulement un code trop petit dans le
     * cadre.
     */
    private fun buildScanner(): FrameScanner {
        val zoom = ZoomHooks(
            maxZoomRatio = maxZoomRatio,
            currentZoomRatio = { camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f },
            applyZoom = { ratio ->
                val cam = camera
                if (cam == null) false else {
                    cam.cameraControl.setZoomRatio(ratio.coerceIn(1f, maxZoomRatio))
                    lastLockOnTimestamp = System.currentTimeMillis()
                    mainHandler.post {
                        state.lockingOn = true
                        state.zoomRatio = ratio
                    }
                    true
                }
            },
        )
        return ZxingFrameScanner(prefs.autoZoomEnabled, zoom)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val currentScanner = scanner
        if (currentScanner == null || processing) {
            imageProxy.close()
            return
        }
        // Lectures de secours (inversion, autre binarisation, débruitage)
        // seulement quand la lecture normale peine depuis un moment : dans le
        // cas courant, elles coûteraient cher pour rien.
        val tryHarder = System.currentTimeMillis() - lastDecodeTimestamp > FALLBACK_AFTER_MS
        // Le scanner rappelle depuis le thread caméra : on ramène tout sur le
        // thread principal, où vit l'état de confirmation multi-frames.
        currentScanner.analyze(imageProxy, tryHarder) { raw, inverted ->
            mainHandler.post { onRawValue(raw, inverted) }
        }
    }

    /**
     * Confirmation multi-frames : un décodage sur image floue peut produire un
     * résultat différent d'une frame à l'autre. On exige deux lectures
     * identiques consécutives avant de valider.
     */
    private fun onRawValue(raw: String, inverted: Boolean) {
        // Un résultat posté avant la validation d'un scan précédent peut
        // arriver après : on l'ignore plutôt que de traiter deux fois.
        if (processing) return
        lastDecodeTimestamp = System.currentTimeMillis()
        if (inverted) {
            lastInvertedTimestamp = lastDecodeTimestamp
            mainHandler.post { state.invertedDecode = true }
        }
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
     * Surveillance de l'AF — volontairement PASSIVE.
     *
     * Ne déclenche jamais de mise au point : voir l'avertissement en tête de
     * fichier. Elle se contente de détecter le seul cas où l'AF continu ne
     * peut pas reprendre la main tout seul — un verrouillage hérité d'un
     * tap-to-focus (FOCUSED_LOCKED / NOT_FOCUSED_LOCKED) alors que plus rien
     * ne se décode — et de rendre explicitement la main au mode continu.
     *
     * Éteint aussi l'indicateur d'auto-zoom, qui resterait sinon allumé après
     * un code aperçu puis sorti du champ.
     */
    private val afWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (state.lockingOn && now - lastLockOnTimestamp > 1500) {
                state.lockingOn = false
            }
            if (state.invertedDecode && now - lastInvertedTimestamp > INVERTED_BADGE_MS) {
                state.invertedDecode = false
            }

            val locked = afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            if (!processing && locked &&
                now - lastDecodeTimestamp > AF_STUCK_THRESHOLD_MS &&
                now - lastAfRecovery > AF_RECOVERY_INTERVAL_MS
            ) {
                lastAfRecovery = now
                // cancelFocusAndMetering() REND la main à l'AF continu.
                // Surtout pas startFocusAndMetering(), qui la lui retirerait.
                camera?.cameraControl?.cancelFocusAndMetering()
                Log.d(TAG, "AF verrouillé sans décodage : retour à l'AF continu")
            }

            if (prefs.afDiagnosticsEnabled) updateDebugInfo()
            mainHandler.postDelayed(this, AF_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun updateDebugInfo() {
        state.debugInfo = DiagnosticSnapshot(
            afState = afState,
            focusDistanceDiopters = lensFocusDistance,
            cameraLabel = activeCameraLabel,
            lastRead = scanner?.lastReadSummary,
        ).render()
    }

    /**
     * Tap-to-focus UNIQUEMENT — jamais appelé automatiquement.
     * Auto-annulation courte : on veut revenir vite à l'AF continu, un
     * verrouillage prolongé sur un plan obsolète étant précisément ce qui
     * donne l'impression d'un autofocus cassé.
     */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val view = previewView ?: return
        try {
            val point = view.meteringPointFactory.createPoint(x, y, DEFAULT_METERING_POINT_SIZE)
            val action = FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(TAP_FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
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

    /** Alimente le dossier consultable des boîtes déjà scannées (HistoryActivity). */
    private fun recordHistory(cip13: String, name: String, lot: String?, expiryIso: String?) {
        prefs.addHistoryEntry(
            Prefs.HistoryEntry(
                cip13 = cip13,
                name = name,
                lot = lot,
                expiryIso = expiryIso,
                scannedAt = System.currentTimeMillis()
            )
        )
    }

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
            // Proposer un ajout forcé pour les boîtes sans numéro de série
            // (deux boîtes identiques du même lot produisent la même clé).
            if (data.serial == null) {
                state.forceAddAction = { processScan(data, cip13, boxKey) }
            }
            releaseAfterDelay()
            return
        }

        state.forceAddAction = null
        processScan(data, cip13, boxKey)
    }

    private fun processScan(data: Gs1Parser.Gs1Data, cip13: String, boxKey: String) {
        feedback(success = true)
        state.set(
            ScanUiState.Phase.PROCESSING,
            "Recherche du médicament...",
            "CIP $cip13 · périme le ${data.expiryIso?.let { frenchDate(it) } ?: "?"}"
        )

        runInBackground {
            val lookup = api.lookupBlocking(cip13)
            val name = lookup.name ?: "Médicament CIP $cip13"

            val label = buildString {
                append(name)
                lookup.quantityLabel?.let { append(" · $it") }
                data.expiryIso?.let { append(" — périme le ${frenchDate(it)}") }
            }
            val description = buildString {
                append("CIP13 : $cip13")
                lookup.quantityLabel?.let { append("\nConditionnement : $it") }
                lookup.form?.let { append("\nForme : $it") }
                data.lot?.let { append("\nLot : $it") }
                data.serial?.let { append("\nSérie : $it") }
                if (lookup.conditions.isNotEmpty()) {
                    append("\nDélivrance : ${lookup.conditions.joinToString(", ")}")
                }
                data.notices.forEach { append("\n[!] $it") }
                if (lookup.error != null) append("\n(nom non résolu : ${lookup.error})")
            }

            // null = Home Assistant désactivé : le scan reste purement local.
            val result = if (prefs.haEnabled) {
                ha.sendBlocking(HomeAssistant.Item(label, data.expiryIso, description, cip13))
            } else null

            mainHandler.post {
                val entryStatus = when (result) {
                    null -> {
                        prefs.rememberScanned(boxKey)
                        recordHistory(cip13, name, data.lot, data.expiryIso)
                        state.set(
                            ScanUiState.Phase.SUCCESS, name,
                            data.expiryIso?.let { "Périme le ${frenchDate(it)}" }
                        )
                        ScanUiState.EntryStatus.LOCAL
                    }
                    is HomeAssistant.SendResult.Success -> {
                        prefs.rememberScanned(boxKey)
                        recordHistory(cip13, name, data.lot, data.expiryIso)
                        state.set(
                            ScanUiState.Phase.SUCCESS, name,
                            data.expiryIso?.let { "Périme le ${frenchDate(it)}" }
                        )
                        ScanUiState.EntryStatus.SENT
                    }
                    is HomeAssistant.SendResult.Queued -> {
                        prefs.rememberScanned(boxKey)
                        recordHistory(cip13, name, data.lot, data.expiryIso)
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

            // Vidange de la file en arrière-plan, sans bloquer le retour du résultat.
            if (result is HomeAssistant.SendResult.Success) {
                ha.flushQueueBlocking()
                mainHandler.post { refreshQueueBadge() }
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
        runInBackground {
            val lookup = api.lookupBlocking(cip13)
            val name = lookup.name ?: "Médicament CIP $cip13"
            val label = buildString {
                append(name)
                expiryIso?.let { append(" — périme le ${frenchDate(it)}") }
            }
            val result = if (prefs.haEnabled) {
                ha.sendBlocking(HomeAssistant.Item(label, expiryIso, "CIP13 : $cip13 (saisie manuelle)"))
            } else null
            mainHandler.post {
                val entryStatus = when (result) {
                    null -> {
                        recordHistory(cip13, name, lot = null, expiryIso)
                        state.set(ScanUiState.Phase.SUCCESS, name, "Ajouté à l'historique")
                        ScanUiState.EntryStatus.LOCAL
                    }
                    is HomeAssistant.SendResult.Success -> {
                        recordHistory(cip13, name, lot = null, expiryIso)
                        state.set(ScanUiState.Phase.SUCCESS, name, "Ajouté à Home Assistant")
                        ScanUiState.EntryStatus.SENT
                    }
                    is HomeAssistant.SendResult.Queued -> {
                        recordHistory(cip13, name, lot = null, expiryIso)
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
        if (!prefs.haEnabled) return
        runInBackground {
            val before = ha.queueSize()
            if (before == 0) {
                if (announceEmpty) mainHandler.post {
                    state.set(state.phase, state.headline, "Rien en attente, tout est synchronisé")
                }
                return@runInBackground
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
        if (!prefs.haEnabled) {
            state.pendingCount = 0
            return
        }
        runInBackground {
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



    companion object {
        private const val TAG = "PharmaScan"
        /** Lectures identiques consécutives exigées avant validation. */
        private const val REQUIRED_CONSECUTIVE_READS = 2
        /** Pause après un scan traité, pour éviter de relire la même boîte. */
        private const val RESCAN_COOLDOWN_MS = 1600L
        /** Taille du point de mesure d'un tap-to-focus (fraction du cadre). */
        private const val DEFAULT_METERING_POINT_SIZE = 0.15f
        /** Retour à l'AF continu après un tap : court, pour ne pas rester verrouillé. */
        private const val TAP_FOCUS_AUTO_CANCEL_SECONDS = 2L
        /** Cadence de la surveillance passive de l'AF. */
        private const val AF_WATCHDOG_INTERVAL_MS = 700L
        /** Sans décodage pendant ce délai, un AF verrouillé est considéré comme bloqué. */
        private const val AF_STUCK_THRESHOLD_MS = 1500L
        /** Espacement minimal entre deux retours forcés à l'AF continu. */
        private const val AF_RECOVERY_INTERVAL_MS = 2500L
        /**
         * Délai sans décodage avant d'essayer aussi les lectures de secours
         * (inversée, autre binarisation, débruitée). Court, pour ne pas faire
         * attendre sur un emballage noir, mais non nul : dans le cas courant
         * elles doublent le coût de chaque image pour rien.
         */
        private const val FALLBACK_AFTER_MS = 600L
        /** Durée d'affichage du badge « code inversé ». */
        private const val INVERTED_BADGE_MS = 2500L
    }
}
