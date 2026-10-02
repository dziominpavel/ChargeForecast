import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

val shouldBumpPatch = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("assemble", ignoreCase = true) ||
        taskName.contains("install", ignoreCase = true) ||
        taskName.contains("bundle", ignoreCase = true)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Подпись релиза для витрины (store): путь и пароли — только в local.properties (вне git).
// Кейстора нет (CI/свежая машина) — release подписывается debug-ключом: сборка всё равно
// остаётся установляемой (пустой signingConfig дал бы unsigned APK, который Android не ставит).
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreProp = signingProps.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseKeystore = releaseStoreProp != null && rootProject.file(releaseStoreProp).exists()
if (releaseStoreProp != null && !hasReleaseKeystore) {
    logger.warn("RELEASE_STORE_FILE=$releaseStoreProp, но файла нет — release подписывается debug-ключом!")
}

android {
    namespace = "com.example.chargeforecast"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.chargeforecast"
        minSdk = 29
        targetSdk = 36

        val versionPropsFile = rootProject.file("version.properties")
        if (!versionPropsFile.exists()) {
            val defaults = Properties().apply {
                setProperty("VERSION_MAJOR", "0")
                setProperty("VERSION_MINOR", "1")
                setProperty("VERSION_PATCH", "0")
            }
            versionPropsFile.parentFile?.mkdirs()
            versionPropsFile.outputStream().use { defaults.store(it, null) }
        }
        val versionProps = Properties().apply {
            versionPropsFile.inputStream().use { load(it) }
        }

        val major = versionProps.getProperty("VERSION_MAJOR", "0").toInt()
        val minor = versionProps.getProperty("VERSION_MINOR", "1").toInt()
        var patch = versionProps.getProperty("VERSION_PATCH", "0").toInt()

        if (shouldBumpPatch) {
            patch += 1
            versionProps.setProperty("VERSION_PATCH", patch.toString())
            versionPropsFile.outputStream().use { versionProps.store(it, null) }
        }

        versionCode = 2_000_000_000 + (major * 10000) + (minor * 100) + patch
        versionName = "$major.$minor.$patch"

        buildConfigField("int", "VERSION_CODE", versionCode.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")

        logger.lifecycle("[ChargeForecast] versionName=$versionName, versionCode=$versionCode, buildDate=$buildDate")
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreProp!!)
                storePassword = signingProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = signingProps.getProperty("RELEASE_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = signingProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        // Доступность: иконки/изображения без contentDescription — ошибка сборки.
        // Декоративные иконки помечаются явным `contentDescription = null`.
        error += "ContentDescription"
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
