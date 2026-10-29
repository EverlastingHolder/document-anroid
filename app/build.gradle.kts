import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val ocrBaseUrl = providers.gradleProperty("OCR_BASE_URL").orElse("").get().trim()
val ocrUseStub = providers.gradleProperty("OCR_USE_STUB").orElse(ocrBaseUrl.isBlank().toString()).get().toBooleanStrictOrNull()
    ?: throw GradleException("OCR_USE_STUB must be exactly 'true' or 'false'.")

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun parsedUrl(value: String): URI? = value.takeIf(String::isNotBlank)?.let {
    runCatching { URI(it) }.getOrNull()
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task -> task.path.contains("release", ignoreCase = true) }
    if (releaseRequested && !ocrUseStub && ocrBaseUrl.isNotBlank() &&
        !parsedUrl(ocrBaseUrl)?.scheme.equals("https", ignoreCase = true)
    ) {
        throw GradleException("OCR_BASE_URL must use HTTPS for release builds.")
    }
}

android {
    namespace = "ru.passport.scanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.passport.scanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OCR_BASE_URL", buildConfigString(ocrBaseUrl))
        buildConfigField("boolean", "OCR_USE_STUB", ocrUseStub.toString())
    }

    buildTypes {
        debug {
            buildConfigField("String", "OCR_BASE_URL", buildConfigString(ocrBaseUrl))
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "OCR_BASE_URL", buildConfigString(ocrBaseUrl))
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
