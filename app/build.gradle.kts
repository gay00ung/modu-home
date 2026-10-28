import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val routingBaseUrl = providers.gradleProperty("routingBaseUrl").orNull
fun stringConstant(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    useLibrary("android.car", false)
    namespace = "io.moduhome.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.moduhome.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "ROUTING_BASE_URL", stringConstant(routingBaseUrl.orEmpty()))
    }

    buildTypes {
        debug {
            buildConfigField("String", "ROUTING_BASE_URL", stringConstant(routingBaseUrl ?: "http://10.0.2.2:5001"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.pleos.speech)
    // Keep the Java bridge and native library together on the 16 KB-compatible AAR.
    implementation(libs.jna) { artifact { type = "aar"; extension = "aar" } }
    implementation(libs.jsr305)
    implementation(libs.zxing.core)
    implementation(libs.pleos.navi.helper)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
