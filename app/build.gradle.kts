plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tom.pharmascan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tom.pharmascan"
        // 24 = minimum requis par CameraX et ML Kit.
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.8"
    }

    buildTypes {
        release {
            // Pas d'obfuscation : R8 sans règles adaptées casse la réflexion
            // utilisée par ML Kit, et l'appli est personnelle.
            isMinifyEnabled = false
        }
    }

    // Deux variantes, seule la bibliothèque de lecture des codes change :
    //  - play   : ML Kit (propriétaire, meilleure détection) → Google Play ;
    //  - fdroid : zxing-cpp (Apache 2.0) → F-Droid, qui refuse les blobs
    //             propriétaires.
    flavorDimensions += "dist"
    productFlavors {
        create("play") { dimension = "dist" }
        create("fdroid") { dimension = "dist" }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // API CameraX marquées expérimentales : Camera2Interop (autofocus
        // continu forcé) et ImageProxy.image (lecture ML Kit).
        freeCompilerArgs.addAll(
            "-opt-in=androidx.camera.camera2.interop.ExperimentalCamera2Interop",
            "-opt-in=androidx.camera.core.ExperimentalGetImage",
        )
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

    // --- Lecture des DataMatrix (une bibliothèque par variante) ---
    // ML Kit 17.3.0 minimum : ZoomSuggestionOptions (auto-zoom) n'existe pas
    // avant. Version "bundled" : le modèle est embarqué, donc le scan
    // fonctionne dès le premier lancement et hors ligne (~3 Mo d'APK).
    "playImplementation"("com.google.mlkit:barcode-scanning:17.3.0")

    // zxing-cpp tire camera-core 1.6.x en dépendance transitive, ce qui
    // déséquilibrerait nos autres modules CameraX (1.3.x). Le wrapper n'utilise
    // que l'API ImageProxy, présente dans 1.3.4 : on exclut donc la transitive.
    "fdroidImplementation"("io.github.zxing-cpp:android:3.1.1") {
        exclude(group = "androidx.camera")
    }

    // --- Divers ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Tests JVM (./gradlew testDebugUnitTest) ---
    testImplementation("junit:junit:4.13.2")
    // org.json est un stub vide dans les tests JVM d'Android : on fournit la vraie.
    testImplementation("org.json:json:20240303")
}
