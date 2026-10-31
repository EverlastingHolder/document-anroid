import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val httpHost: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val host = httpHost.get().takeIf(String::isNotBlank)?.let { value ->
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }
        val config = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<network-security-config>")
            appendLine("    <base-config cleartextTrafficPermitted=\"false\" />")
            if (host != null) {
                appendLine("    <domain-config cleartextTrafficPermitted=\"true\">")
                appendLine("        <domain includeSubdomains=\"false\">$host</domain>")
                appendLine("    </domain-config>")
            }
            appendLine("</network-security-config>")
        }
        val output = outputDirectory.file("xml/network_security_config.xml").get().asFile
        output.parentFile.mkdirs()
        output.writeText(config)
    }
}

val ocrBaseUrl = providers.gradleProperty("OCR_BASE_URL")
    .orElse("http://192.168.1.91:8080")
    .get()
    .trim()
val ocrUseStub = providers.gradleProperty("OCR_USE_STUB").orElse(ocrBaseUrl.isBlank().toString()).get().toBooleanStrictOrNull()
    ?: throw GradleException("OCR_USE_STUB must be exactly 'true' or 'false'.")

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val ocrBaseUri = ocrBaseUrl.takeIf(String::isNotBlank)?.let { value ->
    val uri = runCatching { URI(value) }.getOrElse {
        throw GradleException("OCR_BASE_URL must be a valid absolute HTTP or HTTPS URL.", it)
    }
    val isHttp = uri.scheme?.equals("http", ignoreCase = true) == true
    val isHttps = uri.scheme?.equals("https", ignoreCase = true) == true
    if (uri.isOpaque || !isHttp && !isHttps || uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.port !in -1..65535 || uri.port == 0
    ) {
        throw GradleException("OCR_BASE_URL must use HTTP or HTTPS, include a host, and omit credentials, query, and fragment.")
    }
    uri
}

val generateNetworkSecurityConfig = tasks.register("generateNetworkSecurityConfig")

androidComponents.onVariants { variant ->
    val variantTask = tasks.register<GenerateNetworkSecurityConfig>(
        "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}NetworkSecurityConfig",
    ) {
        httpHost.set(
            ocrBaseUri
                ?.takeIf { it.scheme.equals("http", ignoreCase = true) }
                ?.host
                .orEmpty(),
        )
    }
    variant.sources.res?.addGeneratedSourceDirectory(
        variantTask,
        GenerateNetworkSecurityConfig::outputDirectory,
    )
    generateNetworkSecurityConfig.configure {
        dependsOn(variantTask)
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
