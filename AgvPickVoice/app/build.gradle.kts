import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.agvtronic.pickvoice"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.agvtronic.pickvoice"
    minSdk = 31 // required by setCommunicationDevice (HFP routing) — doc §2.1
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit — Developer Mode.
    // Empty placeholders = Developer Mode (no registered app needed for local dev,
    // MockDeviceKit, or real glasses with Developer Mode enabled in the Meta AI app).
    // Replace with real values from the Wearables Developer Center only for release builds.
    manifestPlaceholders["mwdat_application_id"] = ""
    manifestPlaceholders["mwdat_client_token"] = ""
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)

  // MockDeviceKit only ships in debug builds — see app/src/debug/.../mockdevice/.
  // Never linked into release, so there is no risk of the mock UI reaching an operator device.
  debugImplementation(libs.mwdat.mockdevice)

  // domain/ and data/ are pure Kotlin by design (doc §3, §11) — plain JVM unit tests,
  // no emulator, no Robolectric.
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
