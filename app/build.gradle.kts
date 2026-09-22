import java.util.Properties

/**
 * Google Books works without a key, but anonymous quotas are low. The key is
 * read from an untracked file so it never lands in version control.
 */
fun readOptionalSecret(name: String): String {
  System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
  val properties = Properties()
  val file = rootProject.file("local.properties")
  if (file.exists()) {
    file.inputStream().use { properties.load(it) }
  }
  return properties.getProperty(name)?.trim().orEmpty()
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.lexiread"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.lexiread.app"
    minSdk = 24
    targetSdk = 36
    // P2-9: Play rejects a second upload with the same versionCode.
    // CI tags releases as v1.0.<run_number>, so derive versionCode from the
    // same counter locally via VERSION_CODE env (fallback 1 for local builds).
    versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()
      ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "GOOGLE_BOOKS_API_KEY", "\"${readOptionalSecret("GOOGLE_BOOKS_API_KEY")}\"")
  }

  // Release signing is optional for local builds: if the upload keystore or
  // its passwords are missing (e.g. fresh checkout without secrets), fall back
  // to the debug keystore so assembleRelease still works. CI provides
  // KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD and gets a real signed build.
  val releaseKeystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
  val hasReleaseKeystore = file(releaseKeystorePath).exists()
    && !System.getenv("STORE_PASSWORD").isNullOrBlank()
    && !System.getenv("KEY_PASSWORD").isNullOrBlank()

  // N-2 (audit 2026-09-17): the debug fallback above is a *local* convenience,
  // but nothing stopped it from applying in CI. A missing
  // RELEASE_KEYSTORE_BASE64 / STORE_PASSWORD / KEY_PASSWORD therefore produced a
  // *successful*, debug-signed "release" that the workflow happily published to
  // the GitHub Release — precisely the outcome P2-10 was meant to prevent, only
  // harder to notice (Play rejects a bundle signed with the wrong key).
  //
  // So: in CI, refuse to build a release artifact without the real key. The check
  // is driven by the requested task names, which keeps test/debug jobs working in
  // CI without upload secrets (they never ask for a release variant).
  // ALLOW_DEBUG_SIGNING=true is the explicit opt-out for a debug-signed release.
  val isCi = !System.getenv("CI").isNullOrBlank()
  val debugSigningAllowed = System.getenv("ALLOW_DEBUG_SIGNING").equals("true", ignoreCase = true)
  val releaseRequested = gradle.startParameter.taskNames.any { requested ->
    val taskName = requested.substringAfterLast(':')
    taskName.contains("Release") || taskName in setOf("assemble", "build", "bundle")
  }
  if (isCi && releaseRequested && !hasReleaseKeystore && !debugSigningAllowed) {
    // Name the specific input that is missing. The three conditions are ANDed
    // above, so a single combined message sends people to the wrong place - and
    // in CI the real cause (a secret that was never configured) is invisible.
    val missing = buildList {
      if (!file(releaseKeystorePath).exists()) {
        add("no keystore file at $releaseKeystorePath")
      }
      if (System.getenv("STORE_PASSWORD").isNullOrBlank()) add("STORE_PASSWORD is not set")
      if (System.getenv("KEY_PASSWORD").isNullOrBlank()) add("KEY_PASSWORD is not set")
    }
    throw GradleException(
      "Refusing to build a release artifact: release signing is not configured - " +
        missing.joinToString("; ") + ". " +
        "In CI these come from the RELEASE_KEYSTORE_BASE64, STORE_PASSWORD and " +
        "KEY_PASSWORD repository secrets (see README, 'Setting up Repository Secrets'). " +
        "Set ALLOW_DEBUG_SIGNING=true to explicitly publish a debug-signed build instead."
    )
  }

  signingConfigs {
    create("release") {
      storeFile = file(releaseKeystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseKeystore) {
        signingConfigs.getByName("release")
      } else {
        logger.warn(
          "Release keystore not found at $releaseKeystorePath or passwords missing - " +
            "signing the release with the debug key. This is a LOCAL-ONLY fallback: in CI " +
            "(CI env set) the build fails instead unless ALLOW_DEBUG_SIGNING=true."
        )
        signingConfigs.getByName("debugConfig")
      }
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  sourceSets {
    // Room's MigrationTestHelper reads the exported schema JSON through the
    // *application* (target context) AssetManager. Under Robolectric that
    // AssetManager is wired to the merged assets of the variant under test —
    // see build/intermediates/unit_test_config_directory/**/test_config.properties
    // (android_merged_assets) — so the schemas must be part of the debug
    // variant's assets. Adding them to the `test` source set does NOT work:
    // unit-test assets are never merged. Debug only, so they never ship in the
    // release APK.
    getByName("debug").assets.srcDir("$projectDir/schemas")
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
// P0-3: GEMINI_API_KEY must never be packaged into the APK. Users provide
// their own key via Settings (stored locally on device), so it is excluded
// from BuildConfig even if present in .env.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("GEMINI_API_KEY")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.jsoup)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.runner)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.turbine)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
