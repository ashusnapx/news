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
        // Anonymous counters (a GA4 web stream), off unless both are set in ~/.gradle/gradle.properties:
        //   countId=G-XXXXXXX   countSecret=xxxxxxxx
        buildConfigField("String", "COUNT_ID", "\"${providers.gradleProperty("countId").getOrElse("")}\"")
        buildConfigField("String", "COUNT_SECRET", "\"${providers.gradleProperty("countSecret").getOrElse("")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // The release key lives outside the repo: ~/.android/upsc-today-release.jks, with its passwords in
        // ~/.gradle/gradle.properties. Lose it and installed copies can never be updated; keep a backup.
        create("release") {
            providers.gradleProperty("releaseStoreFile").orNull?.let { storeFile = file(it) }
            storePassword = providers.gradleProperty("releaseStorePassword").orNull
            keyAlias = providers.gradleProperty("releaseKeyAlias").orNull
            keyPassword = providers.gradleProperty("releaseKeyPassword").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
