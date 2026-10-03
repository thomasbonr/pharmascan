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
        // 24 = minimum requis par CameraX.
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "1.9"

        // zxing-cpp embarque une bibliothèque native par ABI (~1,5 Mo chacune) :
        // on ne garde que les ABI des téléphones (x86 = émulateurs seulement).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // R8 : code mort retiré (material-icons-extended pèse des Mo
            // sinon) et ressources inutilisées supprimées. zxing-cpp fournit
            // sa propre règle keep pour la couche JNI.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Appli personnelle distribuée en APK : signée avec la clé de debug.
            signingConfig = signingConfigs.getByName("debug")
        }
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
        // continu forcé) et ImageProxy.image.
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

    // --- Lecture des DataMatrix : zxing-cpp (Apache 2.0, 100 % libre) ---
    // Il tire camera-core 1.6.x en dépendance transitive, ce qui déséquilibrerait
    // nos autres modules CameraX (1.3.x). Le wrapper n'utilise que l'API
    // ImageProxy, présente dans 1.3.4 : on exclut donc la transitive.
    implementation("io.github.zxing-cpp:android:3.1.1") {
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
