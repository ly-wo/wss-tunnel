import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wlzxj.tunnel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wlzxj.tunnel"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.4"
        testInstrumentationRunner = "com.wlzxj.tunnel.TestRunner"
        testInstrumentationRunnerArguments["tunnel_origin"] = "http://127.0.0.1:19092"
        testInstrumentationRunnerArguments["tunnel_target"] = "http://127.0.0.1:19093"
        ndk {
            abiFilters += listOf("arm64-v8a")
            if (project.findProperty("includeX86") == "true") {
                abiFilters += "x86_64"
            }
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("stableDebug") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_release"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_debug"
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
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
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    testOptions {
        animationsDisabled = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

val repoRoot = rootProject.projectDir.parentFile
val includeX86Jni = project.findProperty("includeX86") == "true"
tasks.register<Exec>("buildJni") {
    group = "build"
    description = "Build Rust JNI libraries for Android"
    workingDir = repoRoot
    commandLine("bash", "scripts/build-android-jni.sh")
    inputs.property("includeX86", includeX86Jni)
    environment("BUILD_ANDROID_X86_64", if (includeX86Jni) "1" else "0")
    inputs.dir(repoRoot.resolve("client/jni/src"))
    inputs.dir(repoRoot.resolve("client/src"))
    inputs.file(repoRoot.resolve("protocol.rs"))
    inputs.file(repoRoot.resolve("Cargo.toml"))
    inputs.file(repoRoot.resolve("Cargo.lock"))
    inputs.file(repoRoot.resolve("client/Cargo.toml"))
    inputs.file(repoRoot.resolve("client/jni/Cargo.toml"))
    inputs.file(repoRoot.resolve(".cargo/config.toml"))
    inputs.file(repoRoot.resolve("rust-toolchain.toml"))
    inputs.file(repoRoot.resolve("scripts/build-android-jni.sh"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn("buildJni")
}
