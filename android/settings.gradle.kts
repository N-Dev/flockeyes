// Flock Eyes: counts dairy cows and recognises each one again.
//   core  plain Kotlin: the cow finder, tracking, recognition, the herd, field and gate counting, exports.
//         Unit-tested on the JVM with the real AI models.
//   app   the Android app: camera, ONNX Runtime, screens, storage.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FlockEyes"
include(":core", ":app")
