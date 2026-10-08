plugins {
    id(com.android.application)
    id(org.jetbrains.kotlin.android)
}

android {
    namespace = com.eden.savetool
    compileSdk = 34

    defaultConfig {
        applicationId = com.eden.savetool
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = 1.0
    }

    signingConfigs {
        create(release) {
             CI 用环境变量，本地调试用 appeden.jks
            val ksPath = System.getenv(KEYSTORE_PATH)
            storeFile = if (ksPath != null) rootProject.file(ksPath) else file(eden.jks)
            storePassword = System.getenv(KEYSTORE_PASSWORD)  changeit123
            keyAlias = System.getenv(KEY_ALIAS)  eden
            keyPassword = System.getenv(KEY_PASSWORD)  changeit123
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(release)
        }
        debug {
            signingConfig = signingConfigs.getByName(release)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = 17
    }
}

dependencies {
    implementation(androidx.appcompatappcompat1.7.0)
    implementation(androidx.activityactivity-ktx1.9.0)
    implementation(androidx.corecore-ktx1.13.1)
}