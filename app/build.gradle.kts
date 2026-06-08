import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.pocketnode.app"
    compileSdk = 35

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile")!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.pocketnode.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-rc1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Adreno OpenCL replaces old Vulkan path — stable on Snapdragon 8 series
                arguments += "-DGGML_OPENCL=ON"
                arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON"
                arguments += "-DGGML_CPU_KLEIDIAI=ON"
                arguments += "-DGGML_OPENMP=ON"
                arguments += "-DGGML_VULKAN=ON"
            }
        }

        // PRO_HMAC_SECRET and PRO_PURCHASE_URL are injected per build type below.
        // They must NOT live here — defaultConfig applies to all variants including release.
        // POCKETNODE_OPERATOR_URL is injected per build type — see buildTypes below.
    }

    buildTypes {
        debug {
            // Debug: permissive fallbacks so local dev works without any env config.
            val operatorUrl = System.getenv("POCKETNODE_OPERATOR_URL")
                ?: "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_0.gguf"
            buildConfigField("String", "POCKETNODE_OPERATOR_URL", "\"$operatorUrl\"")
            val proSecret = System.getenv("POCKETNODE_PRO_HMAC_SECRET") ?: "dev-only-secret-change-me"
            val purchaseUrl = System.getenv("POCKETNODE_PURCHASE_URL") ?: "https://example.com/pocketnode-pro"
            buildConfigField("String", "PRO_HMAC_SECRET", "\"$proSecret\"")
            buildConfigField("String", "PRO_PURCHASE_URL", "\"$purchaseUrl\"")
        }
        release {
            // Secrets are validated at execution time by validateReleaseSecrets (below),
            // not here — configuration-phase throws block assembleDebug too.
            val proSecret = System.getenv("POCKETNODE_PRO_HMAC_SECRET") ?: ""
            val purchaseUrl = System.getenv("POCKETNODE_PURCHASE_URL") ?: ""
            buildConfigField("String", "PRO_HMAC_SECRET", "\"$proSecret\"")
            buildConfigField("String", "PRO_PURCHASE_URL", "\"$purchaseUrl\"")

            // Operator URL: env var → gradle property → empty string (shows "Source not configured").
            // The SmolLM2 test URL must NEVER appear in a release build.
            val operatorUrl = System.getenv("POCKETNODE_OPERATOR_URL")
                ?: (project.findProperty("pocketnode.operator.url") as? String ?: "")
            buildConfigField("String", "POCKETNODE_OPERATOR_URL", "\"$operatorUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Fail-fast guard: runs only when assembleRelease is in the task graph.
// Configuration-phase throws (inside buildTypes{}) also block assembleDebug — don't do that.
tasks.register("validateReleaseSecrets") {
    doFirst {
        val secret = System.getenv("POCKETNODE_PRO_HMAC_SECRET") ?: ""
        if (secret.isBlank()) throw GradleException(
            "POCKETNODE_PRO_HMAC_SECRET is not set. Export the env var before running assembleRelease."
        )
        val url = System.getenv("POCKETNODE_PURCHASE_URL") ?: ""
        if (url.isBlank()) throw GradleException(
            "POCKETNODE_PURCHASE_URL is not set. Export the env var before running assembleRelease."
        )
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        dependsOn("validateReleaseSecrets")
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Document picker
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Markdown Parsing
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")

    // Ktor embedded server for Edge API (CIO engine — no native deps, Android-safe)
    val ktorVersion = "3.0.3"
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // PDF Parsing
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
