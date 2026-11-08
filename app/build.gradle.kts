plugins {
    id("com.android.application")
}

android {
    namespace = "fer.amiya.amiyahooks.gwdfix"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "fer.amiya.amiyahooks.gwdfix"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0-gwd320"

        ndk {
            // 偏移表只适配 arm64 的 libapp.so
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Xposed API 82 不在 Maven Central，直接用随仓库附带的 jar（compileOnly，不打进包）
    compileOnly(files("libs/api-82.jar"))
}
