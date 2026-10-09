plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.wayfinder.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.wayfinder.app"
        minSdk = 26          // ставится и на телефон друга; продвинутое — по разрешениям
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // Чувствительные данные (IP серверов, домен туннеля) НЕ лежат в коде.
        // Они приходят из переменных окружения при сборке:
        //   - локально: экспортируй переменные перед ./gradlew
        //   - в CI: из секретов репозитория (приватны даже у публичного репа)
        // Пусто → серверные/туннельные зонды просто пропускаются. Друзьям — ок.
        buildConfigField(
            "String", "SERVERS_CONFIG",
            "\"${System.getenv("WAYFINDER_SERVERS") ?: ""}\""
        )
        buildConfigField(
            "String", "TUNNEL_DOMAIN",
            "\"${System.getenv("WAYFINDER_TUNNEL") ?: ""}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Постоянный ключ подписи. Благодаря ему каждый новый APK ставится ПОВЕРХ
        // старого как обновление (данные сохраняются), без удаления.
        // Для debug это не секрет — пароли фиксированы, keystore лежит в репе.
        create("fixed") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "wayfinder"
            keyAlias = "wayfinder"
            keyPassword = "wayfinder"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Намеренно минимальный набор: меньше библиотек — меньше конфликтов версий.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
