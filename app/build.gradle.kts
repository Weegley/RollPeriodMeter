plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weegley.rollperiodmeter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.weegley.rollperiodmeter"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        applicationVariants.all {
            outputs.all {
                val variantName = name   // debug / release
               (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "RollPeriodMeter-${variantName}.apk"
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
