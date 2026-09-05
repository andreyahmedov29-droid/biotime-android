plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.biotime.employee"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.biotime.employee"
        minSdk = 26          // Android 8.0: позволяет использовать адаптивные иконки без PNG-фолбэков
        targetSdk = 35
        // Версия APK. Единый источник — version.json в корне репозитория:
        // CI (build-apk.yml) синхронизирует valueCode/versionName из него
        // перед сборкой, поэтому при поднятии версии правьте ТОЛЬКО version.json.
        versionCode = 10
        versionName = "1.0.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подписываем release отладочным ключом, чтобы APK можно было сразу
            // установить и раздать водителям. Для Google Play затем поставьте
            // настоящий signingConfig (keystore).
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Встроенный QR/штрих-код сканер (ZXing) — для этикеток отгрузки.
    // Используется мостом AndroidBridge.scanQR из MainActivity.
    // Координаты: com.journeyapps:zxing-android-embedded (не «barcodescanner»).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
