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
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
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
                implementation(libs.compose.resources)
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
        // The model engine (package engine) runs on the laptop only: the phone decides nothing and loads no model.
        desktopMain.kotlin.srcDir("src/litert/kotlin")
        // litertlm asks for kotlin-reflect 2.4.0, which lifts kotlin-stdlib past this compiler; only its unused ToolSet API reflects.
        desktopMain.dependencies { implementation(libs.litertlm.jvm); implementation("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}!!") }
    }
}

compose.desktop {
    application {
        mainClass = "ch.lauzhack.app.MainKt"
        // Menus follow dark mode; no Dock icon, the tray and the floater are the app.
        jvmArgs("-Dapple.awt.application.appearance=system", "-Dapple.awt.UIElement=true")
        nativeDistributions {
            packageName = "Moliere" // codesign fails on the bundle when it is named Molière
            packageVersion = "1.0.0"
            modules("java.instrument", "java.management", "java.prefs", "java.sql", "jdk.unsupported") // suggestRuntimeModules
            macOS {
                bundleID = "ch.lauzhack.moliere"
                entitlementsFile.set(project.file("entitlements.plist"))
                // The helpers (hear, meet, ssh) are this app's children, so macOS asks on this app's behalf, in these words.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleDisplayName</key><string>Molière</string>
                        <key>NSMicrophoneUsageDescription</key><string>Molière listens to what you say in a call to put the right thing on the shared screen.</string>
                        <key>NSSpeechRecognitionUsageDescription</key><string>Molière turns what you say into text, on this Mac.</string>
                        <key>NSCalendarsFullAccessUsageDescription</key><string>Molière shows the title of the meeting you are in, from your calendar.</string>
                        <key>NSCalendarsUsageDescription</key><string>Molière shows the title of the meeting you are in, from your calendar.</string>
                        <key>NSLocalNetworkUsageDescription</key><string>Molière talks to your phone and to the shared screen on this network.</string>
                        <key>LSUIElement</key><true/>
                    """.trimIndent()
                }
            }
        }
    }
}

// --typed reads lines from the terminal under ./gradlew :composeApp:run too.
tasks.matching { it.name == "run" }.configureEach { doFirst { (this as JavaExec).standardInput = System.`in` } }
