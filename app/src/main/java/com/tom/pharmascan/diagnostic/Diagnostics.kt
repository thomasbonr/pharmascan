package com.tom.pharmascan.diagnostic

import android.hardware.camera2.CameraMetadata
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tom.pharmascan.ui.StatusWarning

/**
 * Tout ce qui sert au diagnostic (réglage « Diagnostic » de l'appli) : l'état
 * brut remonté par la caméra et le décodeur, et son rendu à l'écran.
 *
 * Il permet de distinguer « l'AF cherche et n'accroche pas », « l'AF est
 * verrouillé au mauvais endroit » et « la mise au point est bonne mais le
 * décodeur n'y arrive pas », sans brancher adb.
 */
class DiagnosticSnapshot(
    /** CONTROL_AF_STATE de la dernière capture, null si inconnu. */
    val afState: Int?,
    /** LENS_FOCUS_DISTANCE en dioptries (0 = infini), null si inconnu. */
    val focusDistanceDiopters: Float?,
    /** Objectif réellement utilisé. */
    val cameraLabel: String,
    /** Résumé de la dernière lecture réussie (stratégie, durée), null si aucune. */
    val lastRead: String?,
)

fun DiagnosticSnapshot.render(): String {
    val af = when (afState) {
        CameraMetadata.CONTROL_AF_STATE_INACTIVE -> "inactif"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN -> "recherche"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "net"
        CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "recherche (tap)"
        CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "net (verrouillé)"
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "ÉCHEC (verrouillé)"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "flou"
        null -> "n/c"
        else -> "état $afState"
    }
    val distance = focusDistanceDiopters?.let {
        if (it <= 0f) "∞" else "${(1000f / it).toInt()} mm"
    } ?: "?"
    val read = lastRead?.let { "\nlecture : $it" }.orEmpty()
    return "AF $af · $distance · $cameraLabel$read"
}

/** Pastille affichée sur l'écran de scan quand le diagnostic est activé. */
@Composable
fun DiagnosticOverlay(info: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(info != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp)) {
            Text(
                text = info.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = StatusWarning,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
