import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.expressive.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.expressive.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "11.1.8"
        vectorDrawables {
            useSupportLibrary = true
        }
        // в APK остаются только нужные языки ресурсов библиотек
        resourceConfigurations += listOf("ru", "en")

        // ключи GIF-сервисов — из local.properties (он не попадает в git): KLIPY_API_KEY=… и/или GIPHY_API_KEY=…
        val local = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "KLIPY_API_KEY", "\"${local.getProperty("KLIPY_API_KEY", "").trim()}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${local.getProperty("GIPHY_API_KEY", "").trim()}\"")
    }

    // своя подпись релиза — если в local.properties (он не попадает в git) указан keystore:
    // RELEASE_STORE_FILE=…, RELEASE_STORE_PASSWORD=…, RELEASE_KEY_ALIAS=…, RELEASE_KEY_PASSWORD=…
    // Без него релиз подписывается отладочным ключом, как раньше (иначе уже установленные копии не обновятся).
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    signingConfigs {
        getByName("debug") {
            enableV2Signing = true
            enableV3Signing = true
        }
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // ревизия git и путь к проекту не попадают в APK
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // проверки качества исправлений и свайпа гоняются на компьютере, без телефона
        unitTests.isReturnDefaultValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
            // kotlin/*.kotlin_builtins не вырезаем: без них не работает Jackson в SDK Anthropic
            excludes += "DebugProbesKt.bin"
            // служебные файлы лицензий из библиотек SDK Anthropic (Jackson, Apache HttpClient)
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/{LICENSE,LICENSE.txt,LICENSE.md,NOTICE,NOTICE.txt,NOTICE.md}"
            excludes += "META-INF/FastDoubleParser-*"
        }
    }
}

composeCompiler {
    // меньше лишних перекомпозиций: лямбды и нестабильные параметры тоже пропускаются, если не изменились
    enableStrongSkippingMode = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")

    // пароли и логины из Google Паролей (и других служб автозаполнения) в строке подсказок, как в Gboard
    implementation("androidx.autofill:autofill:1.1.0")

    // исправление текста Gemini Nano прямо на телефоне (через AICore, без ключа и интернета)
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    // исправление текста с помощью Claude (ключ API пользователя)
    implementation("com.anthropic:anthropic-java:2.34.0")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")

    // JavaScript для модов (плагинов со скриптами): чистая Java, работает в режиме интерпретатора
    implementation("org.mozilla:rhino:1.7.15")

    // ставит baseline-профили Compose при установке: быстрее первый показ клавиатуры и прокрутка
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    testImplementation("junit:junit:4.13.2")
}
