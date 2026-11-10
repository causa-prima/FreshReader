import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * A release's version, from its tag `vYYYY.M.D.N` (N counts the day's releases from 0), passed as `-PreleaseTag=…`.
 * The version code is YYMMDDNN, which grows with every release. Builds without a tag are version `dev`.
 */
fun version(tag: String?): Pair<String, Int> {
    if (tag == null) return "dev" to 1
    val numbers = Regex("""v(20\d\d)\.([1-9]\d?)\.([1-9]\d?)\.(0|[1-9]\d?)""").matchEntire(tag)
        ?.destructured?.toList()?.map(String::toInt)
        ?.takeIf { (year, month, day) -> runCatching { LocalDate.of(year, month, day) }.isSuccess }
        ?: throw GradleException("The release tag '$tag' is not vYYYY.M.D.N, e.g. v2026.11.10.0 (no leading zeros)")
    val (year, month, day, release) = numbers
    return tag.removePrefix("v") to (year - 2000) * 1_000_000 + month * 10_000 + day * 100 + release
}

val releaseTag: String? = providers.gradleProperty("releaseTag").orNull
val (releaseVersionName, releaseVersionCode) = version(releaseTag)

/** The release signing key, a PKCS12 keystore whose key has the store's password; see docs/releasing.md. */
val keystore: String? = providers.environmentVariable("FRESHREADER_KEYSTORE").orNull
if (releaseTag != null && keystore == null) throw GradleException("A release needs FRESHREADER_KEYSTORE to be signed")

android {
    namespace = "io.github.causaprima.freshreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.causaprima.freshreader"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("FRESHREADER_KEYSTORE_PASSWORD").get()
            keyAlias = "freshreader"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
        // Installable beside a release, under its own name and icon (src/debug).
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // Google's encrypted dependency report; F-Droid does not accept APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.reorderable)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
