plugins {
    kotlin("jvm") version "1.8.0" apply false
    id("com.android.application") version "8.0.0" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
