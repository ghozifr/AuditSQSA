plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.hilt)
    alias(libs.plugins.legacy.kapt)
}

// ---------------------------------------------------------------------------
// Tanda tangan rilis.
//
// TANPA ini, `assembleRelease` menghasilkan app-release-unsigned.apk dan Android
// MENOLAK menginstalnya. AAB untuk Play tetap aman (Play App Signing).
//
// Buat berkas keystore.properties di root project (sudah masuk .gitignore):
//   storeFile=C:/Users/andri/AndroidStudioProjects/Keystore/Suruhaja
//   storePassword=...
//   keyAlias=key0
//   keyPassword=...
// ---------------------------------------------------------------------------
val ksFile = rootProject.file("keystore.properties")
val hasKeystoreProps = ksFile.exists()
val ksValues: Map<String, String> = if (hasKeystoreProps) {
    ksFile.readLines()
        .mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
        }
        .toMap()
} else {
    emptyMap()
}


android {
    namespace = "com.suruhaja"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.suruhaja"
        minSdk = 24
        targetSdk = 36
        versionCode = 25
        versionName = "25.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps API key placeholder — ganti dengan key asli nanti
        manifestPlaceholders["MAPS_API_KEY"] = "AIzaSyDNn2KVkwvnyOLaLGCOUTntD7ZdzzNoFL0"
    }


    signingConfigs {
        if (hasKeystoreProps) {
            create("release") {
                storeFile = rootProject.file(ksValues["storeFile"].orEmpty())
                storePassword = ksValues["storePassword"]
                keyAlias = ksValues["keyAlias"]
                keyPassword = ksValues["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            // Play Console: "Obfuscation 2%" + "No R8 metadata included" -> R8 wajib aktif.
            // Hati-hati: model Firestore dibaca reflektif, aturannya ada di proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProps) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Glide
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    // QR Code (ZXing)
    implementation(libs.zxing.core)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Google Play In-App Updates
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Google Maps
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit)
    // org.json ASLI untuk unit test: versi bawaan android.jar hanya stub yang
    // melempar RuntimeException, jadi tes baca/tulis JSON selalu gagal tanpa ini.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
