import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fingertip.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fingertip.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.1.9"
        // 仅打包 arm ABI（手机只需 arm；x86/x86_64 仅用于模拟器）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 仅保留中文资源，避免全量多语言 string 打包
        resConfigs("zh")
    }

    // 临时签名：用 Android 默认 debug keystore 给 release 自测签名。
    // ⚠️ 正式发布前务必替换为自有 keystore（否则无法上架/覆盖安装）。
    signingConfigs {
        create("release") {
            storeFile = File(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyPassword = "android"
            keyAlias = "androiddebugkey"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        // 生成 BuildConfig，供关于页取实时 VERSION_NAME（避免版本号写死过期）
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-core 已包含项目实际用到的 3 个图标
    // （Settings / ChevronRight / AutoMirrored ArrowBack），去掉巨型 -extended
    implementation("androidx.compose.material:material-icons-core")
    // 通信
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
