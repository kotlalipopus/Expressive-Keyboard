import java.util.Properties
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

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
        versionCode = 26
        versionName = "11.2.2"
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
        // API Material 3 Expressive (MaterialShapes, MotionScheme.expressive, LoadingIndicator) пока помечены экспериментальными
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // проверки качества исправлений и свайпа гоняются на компьютере, без телефона
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // lint-проверки из material3 1.5 (Expressive) собраны под более новый AGP и роняют lintVital — сборку не прерываем
        checkReleaseBuilds = false
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

// ---------------------------------------------------------------------------------------------
// Ротация ключа подписи (APK Signature Scheme v3).
// 11.2.0 был собран в CI чужим ключом, и у всех, кто его поставил, подпись отличается от 11.1.9 и старше.
// Release APK подписывается цепочкой «ключ 11.2.0 → наш ключ» (signing/rotation.lineage):
// Android 9+ ставит обновление поверх обеих подписей без удаления приложения и без потери словаря.
// Старый ключ хранится вне репозитория, путь и пароли — в local.properties (ROTATION_OLD_*).
// ---------------------------------------------------------------------------------------------
abstract class RotationSignApk : DefaultTask() {
    @get:Internal abstract val apkDir: DirectoryProperty
    @get:Internal abstract val lineage: RegularFileProperty
    @get:Internal abstract val oldStore: RegularFileProperty
    @get:Internal abstract val oldStorePass: Property<String>
    @get:Internal abstract val oldAlias: Property<String>
    @get:Internal abstract val oldKeyPass: Property<String>
    @get:Internal abstract val newStore: RegularFileProperty
    @get:Internal abstract val newStorePass: Property<String>
    @get:Internal abstract val newAlias: Property<String>
    @get:Internal abstract val newKeyPass: Property<String>
    @get:Internal abstract val minSdk: Property<Int>

    private fun signer(name: String, store: File, storePass: String, alias: String, keyPass: String): com.android.apksig.ApkSigner.SignerConfig {
        val ks = runCatching {
            KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, storePass.toCharArray()) } }
        }.getOrElse {
            KeyStore.getInstance("JKS").apply { store.inputStream().use { load(it, storePass.toCharArray()) } }
        }
        val key = ks.getKey(alias, keyPass.toCharArray()) as PrivateKey
        val certs = ks.getCertificateChain(alias).map { it as X509Certificate }
        return com.android.apksig.ApkSigner.SignerConfig.Builder(name, key, certs).build()
    }

    @TaskAction
    fun sign() {
        val lin = com.android.apksig.SigningCertificateLineage.readFromFile(lineage.get().asFile)
        val old = signer("OLD", oldStore.get().asFile, oldStorePass.get(), oldAlias.get(), oldKeyPass.get())
        val new = signer("CERT", newStore.get().asFile, newStorePass.get(), newAlias.get(), newKeyPass.get())
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "Release APK не найден в ${apkDir.get().asFile}" }
        for (apk in apks) {
            val src = File(apk.parentFile, apk.name + ".unrotated")
            apk.copyTo(src, overwrite = true)
            com.android.apksig.ApkSigner.Builder(listOf(old, new))
                .setSigningCertificateLineage(lin)
                .setMinSdkVersion(minSdk.get())
                .setMinSdkVersionForRotation(28)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setInputApk(src)
                .setOutputApk(apk)
                .build()
                .sign()
            src.delete()
            val r = com.android.apksig.ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk.get()).build().verify()
            check(r.isVerified && r.isVerifiedUsingV3Scheme) { "Подпись ${apk.name} не прошла проверку: ${r.errors}" }
            val got = r.v3SchemeSigners.first().certificate
            check(got == new.certificates.first()) { "${apk.name} подписан не тем ключом" }
            check(r.signingCertificateLineage?.size() == lin.size()) { "В ${apk.name} нет цепочки ротации ключа" }
            logger.lifecycle("${apk.name}: подписан с ротацией ключа (${lin.size()} сертификата в цепочке)")
        }
    }
}

run {
    val lp = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val lineageFile = rootProject.file("signing/rotation.lineage")
    val oldStore = lp.getProperty("ROTATION_OLD_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    val relStore = lp.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    val isCi = System.getenv("CI") == "true"
    if (lineageFile.exists() && oldStore == null && !isCi) {
        throw GradleException(
            "Нет старого ключа для ротации подписи: укажите ROTATION_OLD_STORE_FILE в local.properties. " +
                "Без него обновление не встанет у тех, кто поставил 11.2.0."
        )
    }
    if (lineageFile.exists() && oldStore != null) {
        val rotate = tasks.register<RotationSignApk>("rotationSignRelease") {
            dependsOn("packageRelease")
            apkDir.set(layout.buildDirectory.dir("outputs/apk/release"))
            lineage.set(lineageFile)
            this.oldStore.set(oldStore)
            oldStorePass.set(lp.getProperty("ROTATION_OLD_STORE_PASSWORD", ""))
            oldAlias.set(lp.getProperty("ROTATION_OLD_KEY_ALIAS", "release"))
            oldKeyPass.set(lp.getProperty("ROTATION_OLD_KEY_PASSWORD", lp.getProperty("ROTATION_OLD_STORE_PASSWORD", "")))
            if (relStore != null) {
                newStore.set(relStore)
                newStorePass.set(lp.getProperty("RELEASE_STORE_PASSWORD"))
                newAlias.set(lp.getProperty("RELEASE_KEY_ALIAS"))
                newKeyPass.set(lp.getProperty("RELEASE_KEY_PASSWORD"))
            } else {
                newStore.set(File(System.getProperty("user.home"), ".android/debug.keystore"))
                newStorePass.set("android")
                newAlias.set("androiddebugkey")
                newKeyPass.set("android")
            }
            minSdk.set(26)
            outputs.upToDateWhen { false }
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(rotate) }
    }
}

composeCompiler {
    // меньше лишних перекомпозиций: лямбды и нестабильные параметры тоже пропускаются, если не изменились
    enableStrongSkippingMode = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
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
    implementation("androidx.compose.material3:material3:1.5.0-alpha13")
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
