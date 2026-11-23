import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.vibepop"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val storeFileName = keystoreProperties.getProperty("storeFile") ?: "vibepop-release.jks"
                val storePass = keystoreProperties.getProperty("storePassword")
                val keyAliasVal = keystoreProperties.getProperty("keyAlias")
                val keyPass = keystoreProperties.getProperty("keyPassword")
                val targetFile = if (file(storeFileName).exists()) {
                    file(storeFileName)
                } else if (rootProject.file(storeFileName).exists()) {
                    rootProject.file(storeFileName)
                } else null

                if (targetFile != null && !storePass.isNullOrBlank() && !keyAliasVal.isNullOrBlank()) {
                    storeFile = targetFile
                    storePassword = storePass
                    keyAlias = keyAliasVal
                    keyPassword = if (!keyPass.isNullOrBlank()) keyPass else storePass
                }
            }
        }
    }

    defaultConfig {
        applicationId = "com.vibepop"
        minSdk = 26
        targetSdk = 34
        // Le changelog est la source de vérité du fork FR. Le buildNumber reste
        // accepté pour reproduire d'anciens builds de CI.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
        val changelogVersion = rootProject.file("CHANGELOG.md")
            .takeIf { it.exists() }
            ?.readText()
            ?.let { Regex("""(?m)^### \[(\d{4}\.\d{2}\.\d+)]""").find(it)?.groupValues?.get(1) }
        val releaseVersion = (project.findProperty("frVersion") as String?) ?: changelogVersion
        val releaseVersionCode = (project.findProperty("frVersionCode") as String?)?.toIntOrNull()
        val calVerCode = releaseVersion
            ?.let { Regex("""(\d{4})\.(\d{2})\.(\d+)""").matchEntire(it) }
            ?.let { match ->
                match.groupValues[1].toInt() * 10_000 +
                    match.groupValues[2].toInt() * 100 +
                    match.groupValues[3].toInt()
            }
        versionCode = releaseVersionCode ?: calVerCode ?: buildNumber?.let { 1_000_000 + it } ?: 4
        versionName = releaseVersion ?: buildNumber?.let { "1.1.2-fr.$it" } ?: "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.lottie)

    testImplementation(libs.junit)
}
