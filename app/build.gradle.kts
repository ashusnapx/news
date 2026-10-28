plugins {
    id("com.android.application")
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
