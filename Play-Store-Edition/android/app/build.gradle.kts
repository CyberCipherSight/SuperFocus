plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Public Play source snapshot: no alternate advanced build target.
val playEdition = true
val testingConnectivity = false

android {
    namespace = "com.cybersight.hyperfocus.appblocker"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    sourceSets.getByName("main").java.srcDir(
        if (playEdition) "src/playEdition/kotlin" else "src/advancedEdition/kotlin"
    )
    if (playEdition) {
        sourceSets.getByName("release").manifest.srcFile("src/playEdition/AndroidManifest.xml")
        sourceSets.getByName("release").res.srcDir("src/playEdition/res")
    }

    defaultConfig {
        applicationId = "com.cybersight.hyperfocus.appblocker"
        buildConfigField("boolean", "PLAY_EDITION", playEdition.toString())
        buildConfigField("boolean", "TESTING_CONNECTIVITY", testingConnectivity.toString())
        manifestPlaceholders["appLabel"] = if (testingConnectivity) "FocusBro Testing" else "FocusBro"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // API 28: setLockTaskFeatures. Below this, lock-task mode cannot keep
        // GLOBAL_ACTIONS enabled, which §2.5 requires. No compat branch.
        minSdk = 28
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    testOptions {
        // Pure Kotlin under test never touches the framework; the few
        // android.* references that do slip in return defaults.
        unitTests.isReturnDefaultValues = true
    }

    // Play uploads use a separately managed key when both variables are set.
    // Local builds keep the existing debug signing identity.
    val playKeystore = System.getenv("FOCUSBRO_UPLOAD_KEYSTORE")
    val playPasswordFile = System.getenv("FOCUSBRO_UPLOAD_PASSWORD_FILE")
    require((playKeystore == null) == (playPasswordFile == null)) {
        "Set both FOCUSBRO_UPLOAD_KEYSTORE and FOCUSBRO_UPLOAD_PASSWORD_FILE"
    }
    val playUpload = if (playKeystore != null && playPasswordFile != null) {
        signingConfigs.create("playUpload") {
            storeFile = file(playKeystore)
            storePassword = file(playPasswordFile).readText().trim()
            keyAlias = "upload"
            keyPassword = storePassword
            storeType = "PKCS12"
        }
    } else null

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = if (playEdition) playUpload else signingConfigs.getByName("debug")
            // Keeps the JNI-registered class name intact under R8 (§24.3).
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
