import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.nasmusicdrop.app"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.nasmusicdrop.app"
    minSdk = 26
    targetSdk = 35
    versionCode = 42
    versionName = "2.3.1"
  }

  signingConfigs {
    create("release") {
      val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
      }
      val ksFile = file("${rootDir}/musicdrop.keystore")
      if (ksFile.exists()) {
        storeFile = ksFile
        storePassword = System.getenv("MUSICDROP_STORE_PASSWORD")
          ?: localProps.getProperty("musicdrop.storePassword")
          ?: (project.findProperty("musicdrop.storePassword") as String?)
        keyAlias = System.getenv("MUSICDROP_KEY_ALIAS")
          ?: localProps.getProperty("musicdrop.keyAlias")
          ?: (project.findProperty("musicdrop.keyAlias") as String?)
        keyPassword = System.getenv("MUSICDROP_KEY_PASSWORD")
          ?: localProps.getProperty("musicdrop.keyPassword")
          ?: (project.findProperty("musicdrop.keyPassword") as String?)
      }
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      val ksFile = file("${rootDir}/musicdrop.keystore")
      if (ksFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation(libs.androidx.compose.ui.tooling)

  // SMBJ para conexión y subida directa con el NAS por SMB sobre Tailscale
  implementation("com.hierynomus:smbj:0.13.0")
  // Coil para cargar carátulas de álbumes (remotas de iTunes y locales)
  implementation("io.coil-kt:coil-compose:2.6.0")

  // Codificador nativo MP3 LAME para Android
  implementation("io.github.lijieqing:lame:1.0.1032")

  // NewPipeExtractor para resolución robusta de YouTube
  implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
}
