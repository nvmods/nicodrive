import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.nicobudget"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("org.json:json:20260719")
}

compose.desktop {
    application {
        mainClass = "com.nicobudget.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NicoBudget"
            packageVersion = "0.1.0"
            description = "NicoBudget Desktop - budget, statistiques, Drive et menus"
            vendor = "NicoBudget"
            includeAllModules = true

            windows {
                packageVersion = "0.1.0"
                dirChooser = true
                perUserInstall = true
                menuGroup = "NicoBudget"
                upgradeUuid = "c4750f0d-a3ae-4bf9-9f5e-7cd618fcb7c3"
            }
        }
    }
}
