// Build racine du projet.
// Depuis Kotlin 2.0, le compilateur Compose est un plugin Gradle versionné
// avec Kotlin : les deux plugins ci-dessous doivent garder la MÊME version.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
