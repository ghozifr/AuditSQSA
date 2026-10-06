// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.legacy.kapt) apply false
     id("org.sonarqube") version "5.0.0.4638" 
}

sonar {
    properties {
        property("sonar.projectKey", "NAMA_PROJECT_KEY_ANDA")
        property("sonar.organization", "NAMA_ORGANISASI_ANDA")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}
