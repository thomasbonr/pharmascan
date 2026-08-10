plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tom.pharmascan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tom.pharmascan"
        // 24 = minimum requis par CameraX et ML Kit.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Pas d'obfuscation : R8 sans règles adaptées casse la réflexion
            // utilisée par ML Kit, et l'appli est personnelle.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // DOIT correspondre à la version du plugin Kotlin déclarée dans le
        // build.gradle.kts racine. Table officielle :
        // developer.android.com/jetpack/androidx/releases/compose-kotlin
        // Ici : Kotlin 1.9.24 -> extension 1.5.14
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.camera.camera2.interop.ExperimentalCamera2Interop",
            "-opt-in=androidx.camera.core.ExperimentalGetImage"
        )
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val cameraX = "1.3.4"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // --- Jetpack Compose (BOM : les versions des modules sont alignées) ---
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Fournit Bolt, CloudOff, Keyboard, Visibility... utilisées dans l'UI.
    implementation("androidx.compose.material:material-icons-extended")

    // --- CameraX ---
    // camera-camera2 est requis pour Camera2Interop (autofocus continu forcé)
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // --- ML Kit ---
    // 17.3.0 minimum : ZoomSuggestionOptions (auto-zoom) n'existe pas avant.
    // Version "bundled" : le modèle est embarqué, donc le scan fonctionne dès
    // le premier lancement et hors ligne. Compter ~3 Mo d'APK en plus.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // --- Divers ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
