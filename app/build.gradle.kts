import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

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

        // Версия отделена от сборки (docs/versioning.md, запрет автобампа):
        // versionName — строго из файла `version`, его меняет только release-скрипт.
        val versionFile = rootProject.file("version")
        require(versionFile.exists()) {
            "нет файла version в корне проекта — версия берётся только из него"
        }
        val versionNameFromFile = versionFile.readText(Charsets.UTF_8).trim()
        require(versionNameFromFile.matches(Regex("""^\d+\.\d+\.\d+$"""))) {
            "файл version вне формата MAJOR.MINOR.PATCH: '$versionNameFromFile'"
        }
        versionName = versionNameFromFile

        // versionCode = база + число коммитов: растёт на каждый коммит, поэтому
        // свежая сборка всегда ставится поверх старой. База общая для четырёх
        // Android-проектов и выше прежних значений (таблица — design.md change'а
        // add-release-only-versioning):
        //   GymProgress   2 000 010 634    ChargeForecast 2 000 000 131
        //   VoiceMind     2 000 000 232    Wishlot        2 000 000 128
        // При недоступном git берётся сама база — она всё равно выше всех прежних.
        val commitCount = runCatching {
            // providers.exec — штатный способ читать git на конфигурации:
            // в отличие от ProcessBuilder он разрешён configuration cache,
            // и его результат входит в отпечаток кэша, поэтому при новом
            // коммите версионный код пересчитается, а не останется устаревшим.
            providers.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                workingDir(rootDir)
            }.standardOutput.asText.map { it.trim().toInt() }.get()
        }.getOrElse {
            logger.warn("[ChargeForecast] git rev-list --count HEAD не выполнен (${it.message}) — versionCode = база")
            0
        }
        versionCode = 2_000_100_000 + commitCount

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
