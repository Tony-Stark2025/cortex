import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

val geminiApiKey: String = localProps.getProperty("GEMINI_API_KEY")
    ?: System.getenv("GEMINI_API_KEY")
    ?: ""

val vertexProjectId: String = localProps.getProperty("VERTEX_PROJECT_ID")
    ?: System.getenv("VERTEX_PROJECT_ID")
    ?: ""

val vertexLocation: String = localProps.getProperty("VERTEX_LOCATION")
    ?: System.getenv("VERTEX_LOCATION")
    ?: "us-central1"

val vertexEndpoint: String = localProps.getProperty("VERTEX_PROJECT_ENDPOINT")
    ?: System.getenv("VERTEX_PROJECT_ENDPOINT")
    ?: if (vertexProjectId.isNotBlank()) {
        "https://${vertexLocation}-aiplatform.googleapis.com/v1/projects/${vertexProjectId}/locations/${vertexLocation}/publishers/google/models/gemini-2.0-flash:generateContent"
    } else ""

val revenueCatApiKey: String = localProps.getProperty("REVENUECAT_API_KEY")
    ?: System.getenv("REVENUECAT_API_KEY")
    ?: "test_QZYEbrxJrDCBvdxPTtzfwtNxNcq"

val oneSignalAppId: String = localProps.getProperty("ONESIGNAL_APP_ID")
    ?: System.getenv("ONESIGNAL_APP_ID")
    ?: "35638374-74f0-4873-bc88-2324754e8e32"

android {
    namespace = "com.cortex.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cortex.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "VERTEX_PROJECT_ID", "\"$vertexProjectId\"")
        buildConfigField("String", "VERTEX_LOCATION", "\"$vertexLocation\"")
        buildConfigField("String", "VERTEX_PROJECT_ENDPOINT", "\"$vertexEndpoint\"")
        buildConfigField("String", "REVENUECAT_API_KEY", "\"$revenueCatApiKey\"")
        buildConfigField("String", "ONESIGNAL_APP_ID", "\"$oneSignalAppId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Foldable & Window posture detection
    implementation(libs.androidx.window)

    // RevenueCat Samsung Galaxy Store SDK
    implementation(libs.revenuecat.purchases.galaxy)

    // OneSignal Push Notifications
    implementation(libs.onesignal)

    // Vertex AI / Gemini Networking & JSON serialization
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
