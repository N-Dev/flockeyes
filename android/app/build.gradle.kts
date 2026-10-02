import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ortVersion = "1.22.0"
val repoRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "io.github.ndev.flockeyes"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ndev.flockeyes"
        minSdk = 26
        targetSdk = 35
        // CI sets these from the run number, so every published build installs over the last one.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One key for every build, so updates install over each other. A private key comes from the
    // environment when there is one (CI decodes it from GitHub secrets); otherwise the sideload key in the
    // repository is used. See android/README.md before switching: phones must reinstall once.
    signingConfigs {
        create("sideload") {
            val privateKey = System.getenv("FLOCKEYES_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
            if (privateKey != null) {
                storeFile = privateKey
                storePassword = System.getenv("FLOCKEYES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FLOCKEYES_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "flockeyes"
                keyPassword = System.getenv("FLOCKEYES_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: System.getenv("FLOCKEYES_KEYSTORE_PASSWORD")
            } else {
                storeFile = file("../keystore/flockeyes.jks")
                storePassword = "flockeyes-sideload"
                keyAlias = "flockeyes"
                keyPassword = "flockeyes-sideload"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    // Phones (arm64) and the CI emulator (x86_64) each get their own APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
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

    androidResources {
        // Models are read whole into memory: stored uncompressed they load faster.
        noCompress += listOf("onnx")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // The on-device tests read the repository's test pictures (tests/assets) from the test app's own assets.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir(repoRoot.resolve("tests/assets"))
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/** The AI models and the sample picture come from the repository's models/ and tests/ folders, so there's one copy of each. */
abstract class CopyModels : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val names = sources.files.map { it.name }
        // The recognition model is made by the Models workflow (.github/workflows/models.yml): without it the app can't name cows.
        check("cow-reid.onnx" in names && "cow-reid.json" in names) { "models/cow-reid.onnx is missing: run the Models workflow first" }
        check(names.any { it.endsWith(".jpg") }) { "tests/assets/singles/cow_a1.jpg (the speed test's picture) is missing" }
        for (f in sources.files) {
            val sub = if (f.extension == "jpg") "samples" else "models"
            f.copyTo(File(root, "$sub/${f.name}"), overwrite = true)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val copy = tasks.register<CopyModels>("copy${variant.name.replaceFirstChar { it.uppercase() }}Models") {
            sources.from(fileTree(repoRoot.resolve("models")) { include("*.onnx", "*.json", "*.bin", "LICENSE-YOLOX.txt") })
            // A picture of a cow for the speed test to run on.
            sources.from(fileTree(repoRoot.resolve("tests/assets/singles")) { include("cow_a1.jpg") })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, CopyModels::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
