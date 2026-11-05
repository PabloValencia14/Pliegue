plugins { id("com.android.application") }
android {
    namespace = "es.pablo.periodico"
    compileSdk = 36
    defaultConfig { applicationId = "es.pablo.periodico"; minSdk = 28; targetSdk = 35; versionCode = 6; versionName = "0.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
