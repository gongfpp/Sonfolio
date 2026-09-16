import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Only a path is supplied to Gradle. Keys and passwords remain outside the repository.
val releaseSigningFile = providers.environmentVariable("SONFOLIO_SIGNING_PROPERTIES")
    .orElse(providers.gradleProperty("sonfolioSigningProperties"))
    .orNull
val releaseSigningProperties = releaseSigningFile?.let { path ->
    val config = file(path)
    require(config.isFile) { "找不到发布签名配置文件。" }
    Properties().apply { config.reader(Charsets.UTF_8).use { load(it) } }.also { values ->
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!values.getProperty(key).isNullOrBlank()) { "发布签名配置缺少 $key。" }
        }
        require(file(values.getProperty("storeFile")).isFile) { "找不到发布密钥库。" }
    }
}

val prepareRuntimeAssets by tasks.registering(Sync::class) {
    from("src/main/assets")
    exclude("sense-voice-model.int8.onnx")
    into(layout.buildDirectory.dir("generated/runtimeAssets"))
}

android {
    namespace = "com.gongfpp.sonfolio"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    providers.gradleProperty("sonfolioNdkPath").orNull?.let { ndkPath = it }

    defaultConfig {
        applicationId = "com.gongfpp.sonfolio"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.2.2"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += "-DANDROID_STL=c++_shared"
            providers.gradleProperty("sonfolioLlamaSource").orNull?.let { arguments += "-DFETCHCONTENT_SOURCE_DIR_LLAMA=$it" }
        } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigningProperties?.let { values ->
            create("release") {
                storeFile = file(values.getProperty("storeFile"))
                storeType = values.getProperty("storeType", "PKCS12")
                storePassword = values.getProperty("storePassword")
                keyAlias = values.getProperty("keyAlias")
                keyPassword = values.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningProperties != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    sourceSets.getByName("main").assets.setSrcDirs(listOf(prepareRuntimeAssets.map { it.destinationDir }))
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
}

tasks.named("preBuild") { dependsOn(prepareRuntimeAssets) }

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
