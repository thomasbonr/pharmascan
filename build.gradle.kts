// Build racine du projet.
// La version du plugin Kotlin doit rester cohérente avec
// composeOptions.kotlinCompilerExtensionVersion dans app/build.gradle.kts.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
