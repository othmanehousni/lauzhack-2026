import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "ch.lauzhack.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources { enable = true } // packs composeResources (the Inter font) into the app
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.koog.agents)
            implementation(libs.koog.embeddings.base)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.pdfbox)
            }
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        // The model engine (package engine): LiteRT-LM has the same classes on both targets, so one source dir serves both.
        listOf("desktopMain", "androidMain").forEach { getByName(it).kotlin.srcDir("src/litert/kotlin") }
        // litertlm asks for kotlin-reflect 2.4.0, which lifts kotlin-stdlib past this compiler; only its unused ToolSet API reflects.
        // A strict version (!!) also holds in :androidApp, which resolves this module's dependencies itself.
        val reflect = "org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}!!"
        desktopMain.dependencies { implementation(libs.litertlm.jvm); implementation(reflect) }
        androidMain.dependencies { implementation(libs.litertlm.android); implementation(reflect) }
    }
}

compose.desktop {
    application {
        mainClass = "ch.lauzhack.app.MainKt"
    }
}
