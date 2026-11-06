import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wled.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wled.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "1.3.3"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("wled-tv-release.keystore")
            if (keystoreFile.exists()) {
                val localProps = Properties().apply {
                    val localPropsFile = rootProject.file("local.properties")
                    if (localPropsFile.exists()) {
                        localPropsFile.inputStream().use { load(it) }
                    }
                }
                storeFile = keystoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: localProps.getProperty("release.store.password")
                    ?: "wledtv2026"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: localProps.getProperty("release.key.alias")
                    ?: "wledtv"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: localProps.getProperty("release.key.password")
                    ?: "wledtv2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
        buildConfig = true
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "wled-tv-v${versionName}-${name}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.json:json:20240303")
}
