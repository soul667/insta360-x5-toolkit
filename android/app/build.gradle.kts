plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "com.soul667.x5remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soul667.x5remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Publish the debug APK to GitHub Packages (Maven registry):
//   GITHUB_TOKEN=... ./gradlew publish
// Consumers: https://maven.pkg.github.com/soul667/insta360-x5-toolkit
publishing {
    publications {
        create<MavenPublication>("apk") {
            groupId = "com.soul667"
            artifactId = "x5remote"
            version = "0.1.0"
            artifact(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")) {
                extension = "apk"
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/soul667/insta360-x5-toolkit")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "soul667"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
