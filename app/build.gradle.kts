plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.appmovilpsychorekords"
    buildFeatures {
        resValues = true
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.appmovilpsychorekords"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "psycho_api_url", providers.gradleProperty("psychoApiUrl")
            .orElse("http://10.0.2.2:3000/api").get())
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
