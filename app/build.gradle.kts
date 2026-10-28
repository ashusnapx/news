plugins {
    id("com.android.application")
    id("com.google.gms.google-services") // reads app/google-services.json (git-ignored: it holds the project's API key)
}

android {
    namespace = "com.ashusnapx.news"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ashusnapx.news"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // shortcut: signed with the debug key for personal sideloading; make a real keystore before any store release
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // The app's only dependency: Google Analytics for Firebase, the one route GA4 accepts for app installs.
    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-analytics")
}
