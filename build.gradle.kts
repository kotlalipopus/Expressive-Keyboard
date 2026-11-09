buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // R8 из AGP 8.7.3 портит огромный конструктор KeyboardSettings (VerifyError на телефоне) — берём свежий
        classpath("com.android.tools:r8:8.9.35")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
