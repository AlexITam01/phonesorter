plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.photosorter.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photosorter.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    // v0.2.0: scan/move live in SorterViewModel so rotation does not
    // cancel them. viewModelScope survives configuration changes.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")

    // v0.3.0: WorkManager — scan/move run as foreground workers,
    // so leaving the app (or the system killing the process) does
    // not stop them. Default WorkManager initialisation is used
    // (no custom Configuration / Initializer needed).
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // On-device image labelling only. ML Kit's on-device labeler runs
    // entirely on the phone; it does not send images to Google Cloud.
    // (The *cloud* ML Kit labeler is a different product and is NOT used.)
    implementation("com.google.mlkit:image-labeling:17.0.8")

    // Local TFLite runtime for the OPTIONAL user-supplied NSFW model
    // (assets/nsfw.tflite). No model is bundled in this repo — see README
    // and NsfwClassifier.kt. If the file is absent, classification falls
    // back to heuristics + a user review queue, and never auto-deletes.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
