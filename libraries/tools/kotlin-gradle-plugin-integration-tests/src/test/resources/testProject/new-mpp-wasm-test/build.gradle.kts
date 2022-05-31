plugins {
    kotlin("multiplatform").version("<pluginMarkerVersion>")
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    wasm {
        <JsEngine>()
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
        }
    }
}