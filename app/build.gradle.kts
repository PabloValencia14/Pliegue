plugins { id("com.android.application") }
android {
    namespace = "es.pablo.periodico"
    compileSdk = 36
    defaultConfig { applicationId = "es.pablo.periodico"; minSdk = 28; targetSdk = 35; versionCode = 5; versionName = "0.4.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
