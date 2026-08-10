package com.tom.pharmascan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Écran de scan.
 *
 * Hiérarchie visuelle voulue :
 *   1. le réticule (là où l'œil doit être) ;
 *   2. la carte de statut en bas (ce qui vient de se passer) ;
 *   3. les commandes, discrètes, en périphérie.
 *
 * Le contenu de la caméra est fourni par `cameraPreview`, injecté depuis
 * l'activité, ce qui garde ce fichier purement présentationnel.
 */
@Composable
fun ScannerScreen(
    state: ScanUiState,
    focusPoint: Offset?,
    cameraPreview: @Composable (Modifier) -> Unit,
    onTapFocus: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
    onToggleTorch: () -> Unit,
    onManualEntry: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryQueue: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        cameraPreview(
            Modifier
                .fillMaxSize()
                .pointerInput("tap") {
                    detectTapGestures(onTap = { onTapFocus(it) })
                }
                .pointerInput("zoom") {
                    // detectTransformGestures ne se déclenche qu'à deux doigts
                    // ici, puisqu'on ignore pan et rotation.
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) onZoom(zoom)
                    }
                }
        )

        FocusRing(focusPoint, Modifier.fillMaxSize())

        ReticleOverlay(
            lockingOn = state.lockingOn,
            processing = state.phase == ScanUiState.Phase.PROCESSING,
            modifier = Modifier.fillMaxSize()
        )

        TopBar(
            state = state,
            onToggleTorch = onToggleTorch,
            onOpenSettings = onOpenSettings,
            onRetryQueue = onRetryQueue,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        ZoomBadge(
            ratio = state.zoomRatio,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
        )

        AimHint(
            visible = state.phase == ScanUiState.Phase.READY,
            lockingOn = state.lockingOn,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp)
        )

        BottomPanel(
            state = state,
            onManualEntry = onManualEntry,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ===========================================================================
// Réticule
// ===========================================================================

/**
 * Le cadre occupe 64 % de la largeur : assez grand pour ne pas contraindre la
 * visée, assez petit pour communiquer la distance de travail attendue.
 * Vert = prêt. Ambre = un code est vu mais illisible (l'auto-zoom travaille).
 */
@Composable
private fun ReticleOverlay(
    lockingOn: Boolean,
    processing: Boolean,
    modifier: Modifier = Modifier
) {
    val accent by animateColorAsState(
        targetValue = when {
            processing -> MaterialTheme.colorScheme.primary
            lockingOn -> StatusWarning
            else -> StatusSuccess
        },
        animationSpec = tween(280),
        label = "reticleColor"
    )

    // Léger « souffle » du cadre : signale que l'appli est vivante et en
    // recherche, sans distraire.
    val transition = rememberInfiniteTransition(label = "reticle")
    val breath by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    androidx.compose.foundation.Canvas(modifier) {
        val side = size.width * 0.64f * if (lockingOn) 1f else breath
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f - size.height * 0.04f
        val dim = Color.Black.copy(alpha = 0.58f)

        // Assombrissement en quatre bandes (plus sûr qu'un BlendMode.Clear,
        // qui exige une couche graphique dédiée)
        drawRect(dim, size = Size(size.width, top))
        drawRect(dim, topLeft = Offset(0f, top + side),
            size = Size(size.width, size.height - top - side))
        drawRect(dim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(dim, topLeft = Offset(left + side, top),
            size = Size(size.width - left - side, side))

        drawCorners(left, top, side, accent)

        // Ligne de balayage : uniquement en recherche active, pour ne pas
        // parasiter la lecture du résultat
        if (!processing) {
            val y = top + side * sweep
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to accent.copy(alpha = 0.75f),
                    1f to Color.Transparent
                ),
                start = Offset(left, y),
                end = Offset(left + side, y),
                strokeWidth = 2.5.dp.toPx()
            )
        }
    }
}

private fun DrawScope.drawCorners(left: Float, top: Float, side: Float, color: Color) {
    val len = side * 0.16f
    val stroke = 4.dp.toPx()
    val r = left + side
    val b = top + side
    val corners = listOf(
        Triple(Offset(left, top + len), Offset(left, top), Offset(left + len, top)),
        Triple(Offset(r - len, top), Offset(r, top), Offset(r, top + len)),
        Triple(Offset(left, b - len), Offset(left, b), Offset(left + len, b)),
        Triple(Offset(r - len, b), Offset(r, b), Offset(r, b - len))
    )
    corners.forEach { (a, corner, c) ->
        drawLine(color, a, corner, stroke, cap = StrokeCap.Round)
        drawLine(color, corner, c, stroke, cap = StrokeCap.Round)
    }
}

// ===========================================================================
// Barre supérieure
// ===========================================================================

@Composable
private fun TopBar(
    state: ScanUiState,
    onToggleTorch: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Compteur de session : retour de progression pendant qu'on vide
        // une armoire entière.
        AnimatedVisibility(state.sessionCount > 0, enter = fadeIn(), exit = fadeOut()) {
            GlassChip(
                text = "${state.sessionCount} scannée${if (state.sessionCount > 1) "s" else ""}",
                tint = StatusSuccess
            )
        }

        Spacer(Modifier.weight(1f))

        // N'apparaît que s'il y a réellement quelque chose en attente :
        // un badge à zéro en permanence est du bruit visuel.
        AnimatedVisibility(state.pendingCount > 0, enter = fadeIn(), exit = fadeOut()) {
            GlassChip(
                text = "${state.pendingCount} en attente",
                tint = StatusWarning,
                icon = Icons.Default.CloudOff,
                onClick = onRetryQueue
            )
        }

        Spacer(Modifier.width(6.dp))

        GlassIconButton(
            icon = Icons.Default.Bolt,
            active = state.torchOn,
            contentDescription = "Torche",
            onClick = onToggleTorch
        )
        Spacer(Modifier.width(6.dp))
        GlassIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "Réglages",
            onClick = onOpenSettings
        )
    }
}

/**
 * Affiché seulement au-delà de 1.1x. L'auto-zoom de ML Kit modifie le cadrage
 * sans action de l'utilisateur : sans ce repère, l'image « saute » de façon
 * inexplicable.
 */
@Composable
private fun ZoomBadge(ratio: Float, modifier: Modifier = Modifier) {
    AnimatedVisibility(ratio > 1.1f, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(50)) {
            Text(
                text = String.format(java.util.Locale.US, "%.1fx", ratio),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun AimHint(visible: Boolean, lockingOn: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = if (lockingOn) "Code repéré, zoom en cours..."
                       else "Approche à 8-12 cm du DataMatrix",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// ===========================================================================
// Panneau inférieur
// ===========================================================================

@Composable
private fun BottomPanel(
    state: ScanUiState,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        RecentList(state)
        Spacer(Modifier.height(8.dp))
        StatusCard(state, onManualEntry)
    }
}

/**
 * Carte de statut. Son rôle : qu'on sache d'un coup d'œil, sans lire, si le
 * dernier scan est passé — couleur et icône avant le texte.
 */
@Composable
private fun StatusCard(state: ScanUiState, onManualEntry: () -> Unit) {
    val tint = when (state.phase) {
        ScanUiState.Phase.SUCCESS -> StatusSuccess
        ScanUiState.Phase.REJECTED -> StatusWarning
        ScanUiState.Phase.ERROR -> StatusError
        ScanUiState.Phase.PROCESSING -> MaterialTheme.colorScheme.primary
        else -> StatusNeutral
    }
    val glow by animateFloatAsState(
        targetValue = if (state.phase == ScanUiState.Phase.SUCCESS) 1f else 0f,
        animationSpec = tween(400),
        label = "glow"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF10201A).copy(alpha = 0.94f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.16f + 0.14f * glow)),
                    contentAlignment = Alignment.Center
                ) {
                    when (state.phase) {
                        ScanUiState.Phase.PROCESSING ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = tint
                            )
                        ScanUiState.Phase.SUCCESS ->
                            Icon(Icons.Default.CheckCircle, null, tint = tint,
                                modifier = Modifier.size(20.dp))
                        ScanUiState.Phase.ERROR, ScanUiState.Phase.REJECTED ->
                            Icon(Icons.Default.ErrorOutline, null, tint = tint,
                                modifier = Modifier.size(20.dp))
                        else ->
                            Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        state.headline,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    AnimatedVisibility(
                        state.detail != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Text(
                            state.detail.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = onManualEntry,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Keyboard, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saisir le CIP13 à la main")
            }
        }
    }
}

/**
 * Historique de session. Utile en pratique : quand on enchaîne vingt boîtes,
 * on veut pouvoir vérifier qu'on n'en a pas raté une sans quitter l'écran.
 */
@Composable
private fun RecentList(state: ScanUiState) {
    AnimatedVisibility(
        visible = state.recent.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF10201A).copy(alpha = 0.88f)
            ),
            modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp)
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                state.recent.take(12).forEach { entry ->
                    RecentRow(entry)
                }
            }
        }
    }
}

@Composable
private fun RecentRow(entry: ScanUiState.ScanEntry) {
    val tint = when (entry.status) {
        ScanUiState.EntryStatus.SENT -> StatusSuccess
        ScanUiState.EntryStatus.QUEUED -> StatusWarning
        ScanUiState.EntryStatus.REJECTED -> StatusError
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.cip13,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.38f),
                fontFamily = FontFamily.Monospace
            )
        }
        entry.expiry?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = 0.85f)
            )
        }
    }
}

// ===========================================================================
// Petits composants réutilisés
// ===========================================================================

@Composable
private fun GlassChip(
    text: String,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    AssistChip(
        onClick = onClick ?: {},
        enabled = onClick != null,
        label = {
            Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
        },
        leadingIcon = icon?.let {
            { Icon(it, null, tint = tint, modifier = Modifier.size(15.dp)) }
        },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            disabledContainerColor = Color.Black.copy(alpha = 0.5f)
        ),
        border = null
    )
}

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        else Color.Black.copy(alpha = 0.5f),
        label = "iconBg"
    )
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(
                icon,
                contentDescription,
                tint = if (active) Color(0xFF00281C) else Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

/** Anneau de mise au point affiché à l'endroit touché. */
@Composable
fun FocusRing(position: Offset?, modifier: Modifier = Modifier) {
    if (position == null) return
    val scale by animateFloatAsState(1f, tween(220), label = "focusScale")
    androidx.compose.foundation.Canvas(modifier) {
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = 34.dp.toPx() * scale,
            center = position,
            style = Stroke(width = 1.8.dp.toPx())
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.5f),
            radius = 4.dp.toPx(),
            center = position
        )
    }
}
