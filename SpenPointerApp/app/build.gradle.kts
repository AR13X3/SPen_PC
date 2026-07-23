plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahmed.spenpointer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahmed.spenpointer"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // Samsung S Pen Remote SDK — plain jars (NOT an aar), so no manifest merging happens from them.
    // Drop BOTH sdk-v1.0.0.jar and spenremote-v1.0.1.jar (from SpenRemoteSDK_v1.0.1.zip) into app/libs/.
    // spenremote-v1.0.1.jar depends on sdk-v1.0.0.jar at runtime — omitting it causes NoClassDefFoundError.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
