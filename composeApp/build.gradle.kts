@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)

    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    androidTarget {
        compilations.all {
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            }
        }
    }
    // Compose Multiplatform 1.11+ no longer publishes iosX64 artifacts, so only arm64 targets remain.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // iOS links SQLCipher (and Firebase, which transitively links the system sqlite3) through SwiftPM
    // instead of CocoaPods. See sqlcipher-bridge/ for why the SQLCipher symbols are resolved at runtime.
    swiftPMDependencies {
        iosMinimumDeploymentTarget.set("16.0")

        // Firebase's transitive C++ packages (gRPC, abseil, BoringSSL) expose Clang modules that
        // cinterop cannot import, so only the modules this project actually needs are listed.
        discoverClangModulesImplicitly.set(false)

        localSwiftPackage(
            directory = project.layout.projectDirectory.dir("sqlcipher-bridge"),
            products = listOf("SQLCipherBridge"),
            importedClangModules = listOf("SQLCipherBridge"),
        )
        swiftPackage(
            url = url("https://github.com/firebase/firebase-ios-sdk.git"),
            version = exact(libs.versions.firebase.ios.get()),
            products = listOf(product("FirebaseAnalytics")),
            importedClangModules = listOf("FirebaseAnalytics"),
        )
    }

    sourceSets {

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)

            // Room
            implementation(libs.room.ktx)
            implementation(libs.android.sqlcipher)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Room
            implementation(libs.room.runtime)
            implementation(libs.room.common)
            implementation(libs.androidx.sqlite)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        iosMain.dependencies {
            // SQLite
            implementation(libs.androidx.sqlite.framework)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.desquared.encryptedroom"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.desquared.encryptedroom"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
    implementation(libs.room.runtime)
}
