import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// ONNX Runtime's Java API: the app supplies the Android build (onnxruntime-android), the tests the desktop one.
val ortVersion = "1.22.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    // Settings for replaying clips that aren't in the repository (see ClipsTest): -Dflockeyes.clips=DIR and so on.
    System.getProperties().forEach { (k, v) -> if (k.toString().startsWith("flockeyes.")) systemProperty(k.toString(), v) }
    // The tests read the real models and the test photos from the repository (../../models, ../../tests).
    systemProperty("flockeyes.repo", rootProject.projectDir.parentFile.absolutePath)
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
