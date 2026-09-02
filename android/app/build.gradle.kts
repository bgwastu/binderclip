import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? {
    val value = keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseStoreFile = signingValue("storeFile", "BINDERCLIP_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "BINDERCLIP_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "BINDERCLIP_KEY_ALIAS")
    ?: signingValue("keyAlias", "ANDROID_KEYSTORE_ALIAS")
val releaseKeyPassword = releaseStorePassword

val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)

val releaseSigningConfigured = releaseSigningValues.all { it != null }
val releaseSigningPartiallyConfigured = releaseSigningValues.any { it != null } && !releaseSigningConfigured

if (releaseSigningPartiallyConfigured && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    throw GradleException(
        "Incomplete Android release signing configuration. " +
        "Provide all values in android/keystore.properties (storeFile, storePassword, keyAlias) " +
        "or via BINDERCLIP_STORE_FILE, BINDERCLIP_STORE_PASSWORD, BINDERCLIP_KEY_ALIAS."
    )
}

android {
    namespace = "net.wastu.binderclip"
    compileSdk = 36

    fun gitProvider(vararg args: String, fallback: String): String = runCatching {
        providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() } ?: fallback
    }.getOrDefault(fallback)

    val gitTagVersion = gitProvider("describe", "--tags", "--abbrev=0", fallback = "1.0.0").removePrefix("v")
    val gitCommitCount = gitProvider("rev-list", "--count", "HEAD", fallback = "1").toIntOrNull() ?: 1
    val gitHash = gitProvider("rev-parse", "--short", "HEAD", fallback = "unknown")

    defaultConfig {
        applicationId = "net.wastu.binderclip"
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: gitCommitCount
        versionName = System.getenv("VERSION_NAME") ?: gitTagVersion
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "BinderClip (Debug)"
        }
        release {
            manifestPlaceholders["appLabel"] = "BinderClip"
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            ndk {
                debugSymbolLevel = "FULL"
            }

            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // android.util.Log is not available on the JVM; without this flag, any unit
    // test that exercises code calling Log.* would throw a RuntimeException.
    // returnDefaultValues makes the stub methods return 0/null instead.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

gradle.taskGraph.whenReady {
    val releaseTaskRequested = allTasks.any { task ->
        task.project == project && task.name.contains("Release", ignoreCase = true)
    }

    if (releaseTaskRequested && !releaseSigningConfigured) {
        throw GradleException(
            "Android release signing is not configured. " +
            "Create android/keystore.properties (storeFile, storePassword, keyAlias) " +
                "or set BINDERCLIP_STORE_FILE, BINDERCLIP_STORE_PASSWORD, BINDERCLIP_KEY_ALIAS."
        )
    }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Quickie: CameraX + bundled ML Kit QR scanner, no Google Play services required.
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
