import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kk.tvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kk.tvlauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    lint {
        // 侧载 APK 不上 Google Play，屏蔽 targetSdk 版本警告
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            // 从 signing.properties 读取（不提交到版本库）
            val signingProps = Properties()
            val signingFile = rootProject.file("signing.properties")
            if (signingFile.exists()) signingProps.load(signingFile.inputStream())
            storeFile     = rootProject.file(signingProps.getProperty("storeFile", "kklauncher.jks"))
            storePassword = signingProps.getProperty("storePassword", System.getenv("STORE_PASSWORD") ?: "")
            keyAlias      = signingProps.getProperty("keyAlias", "kklauncher")
            keyPassword   = signingProps.getProperty("keyPassword", System.getenv("KEY_PASSWORD") ?: "")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("armeabi-v7a")
            }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    // Android TV 官方 UI 库（Leanback 焦点导航）
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.recyclerview)
    // 图片加载（应用图标 + 背景图片）
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    // 协程
    implementation(libs.kotlinx.coroutines.android)
    // ViewModel / LiveData
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    // viewModels() 委托扩展
    implementation(libs.androidx.activity.ktx)
    // Dock 配置持久化
    implementation(libs.gson)
    // Material 组件
    implementation(libs.material)
    // SMB 文件访问（背景图从局域网 NAS 读取）
    implementation(libs.jcifs.ng)
    implementation(libs.smbj)
}
