package com.tom.pharmascan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tom.pharmascan.Gs1Parser

/**
 * Repli manuel, indispensable quand le DataMatrix est abîmé, effacé, ou que la
 * boîte est trop ancienne pour être sérialisée. Le CIP13 est de toute façon
 * imprimé en clair à côté du code.
 *
 * La validation est immédiate et réutilise le MÊME contrôle de clé que le
 * scan : une faute de frappe sur un chiffre est détectée avant l'envoi, ce
 * qu'une simple vérification de longueur ne ferait pas.
 */
@Composable
fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onSubmit: (cip13: String, expiryIso: String?) -> Unit
) {
    var cip by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }

    val digits = cip.filter { it.isDigit() }
    val lengthOk = digits.length == 13
    // Le CIP13 porte sa propre clé de contrôle, calculée comme un GTIN-13.
    val checksumOk = lengthOk && Gs1Parser.isValidGtinChecksum(digits)
    val expiryIso = remember(expiry) { parseManualExpiry(expiry) }
    val expiryProvided = expiry.filter { it.isDigit() }.isNotEmpty()

    val cipError = when {
        digits.isEmpty() -> null
        !lengthOk -> "${digits.length}/13 chiffres"
        !checksumOk -> "Clé de contrôle incorrecte, vérifie la saisie"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saisie manuelle") },
        text = {
            Column {
                Text(
                    "Le CIP13 est imprimé en clair sur la boîte, juste à côté du DataMatrix.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = cip,
                    onValueChange = { if (it.length <= 17) cip = it },
                    label = { Text("CIP13") },
                    placeholder = { Text("3400930000007") },
                    singleLine = true,
                    isError = cipError != null,
                    supportingText = cipError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = expiry,
                    onValueChange = { if (it.length <= 7) expiry = it },
                    label = { Text("Péremption (optionnel)") },
                    placeholder = { Text("11/2027") },
                    singleLine = true,
                    isError = expiryProvided && expiryIso == null,
                    supportingText = {
                        Text(
                            when {
                                expiryIso != null -> "Enregistrée au ${frenchDate(expiryIso)} (fin de mois)"
                                expiryProvided -> "Format attendu : MM/AAAA"
                                else -> "Format MM/AAAA"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(digits, expiryIso) },
                enabled = checksumOk && (!expiryProvided || expiryIso != null)
            ) { Text("Ajouter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

/**
 * Accepte "MM/AAAA", "MMAAAA", "MM/AA". Renvoie le dernier jour du mois, ce
 * qui correspond à la convention pharmaceutique : un médicament marqué
 * 11/2027 est utilisable jusqu'au 30 novembre inclus.
 */
private fun parseManualExpiry(input: String): String? {
    val d = input.filter { it.isDigit() }
    return when (d.length) {
        4 -> Gs1Parser.parseExpiry(d.substring(2) + d.substring(0, 2) + "00")   // MMAA
        6 -> Gs1Parser.parseExpiry(d.substring(4) + d.substring(0, 2) + "00")   // MMAAAA
        else -> null
    }
}

private fun frenchDate(iso: String): String {
    val p = iso.split("-")
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
}
