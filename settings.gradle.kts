pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
rootProject.name = "kaffeeverde"

include(":core")
include(":lifecycle")
include(":lifecycle-viewmodel")
include(":lifecycle-viewmodel-savedstate")
include(":navigation-common")
include(":navigation-compose")
include(":navigation-runtime")
include(":application-support")
