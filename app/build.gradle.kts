@file:Suppress("UnstableApiUsage")

import Config.Version.createVersion
import Config.Version.source
import Config.isRelease
import Config.lastCommitSha
import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.parcelize)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.com.google.gms.google.services) apply false
    alias(libs.plugins.com.google.firebase.crashlytics) apply false
    alias(libs.plugins.com.google.firebase.firebase.pref) apply false
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.navigation.safeargs)
    id("com.mikepenz.aboutlibraries.plugin") version "14.2.0"
    id("com.github.ben-manes.versions") version "0.54.0"
}

if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.com.google.gms.google.services.get().pluginId)
    apply(plugin = libs.plugins.com.google.firebase.crashlytics.get().pluginId)
    apply(plugin = libs.plugins.com.google.firebase.firebase.pref.get().pluginId)
}

android {
    compileSdk = property("compile.sdk")?.toString()?.toIntOrNull()

    val commitSha = if (isRelease) lastCommitSha else "b8eace8" // 方便调试

    // Public membership connection settings only. Never put merchant tokens or signing keys here.
    val commerceApiUrl = System.getenv("COMMERCE_API_BASE_URL")
        ?: providers.gradleProperty("commerce.api.url").orNull.orEmpty()
    val commercePublicKey = System.getenv("COMMERCE_PUBLIC_KEY_DER_B64")
        ?: providers.gradleProperty("commerce.public.key.der.base64").orNull.orEmpty()
    val commercePublicKeyClean = commercePublicKey.trim()
    val commerceApiUrlB64 = Base64.getEncoder()
        .encodeToString(commerceApiUrl.toByteArray(Charsets.UTF_8))


    defaultConfig {
        applicationId = "io.github.yishiba.han1meviewer"
        minSdk = property("min.sdk")?.toString()?.toIntOrNull()
        targetSdk = property("target.sdk")?.toString()?.toIntOrNull()
        val (code, name) = createVersion(major = 0, minor = 26, patch = 0)
        versionCode = code
        versionName = name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
        buildConfigField("String", "VERSION_NAME", "\"${versionName}\"")
        buildConfigField("int", "VERSION_CODE", "$versionCode")
        buildConfigField("String", "VERSION_SOURCE", "\"${source}\"")
        buildConfigField("String", "COMMERCE_API_URL_B64", "\"$commerceApiUrlB64\"")
        buildConfigField("String", "COMMERCE_PUBLIC_KEY_DER_B64", "\"$commercePublicKeyClean\"")

        buildConfigField("int", "SEARCH_YEAR_RANGE_END", "${Config.thisYear}")
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("RELEASE_KEYSTORE_PATH")
                ?: (System.getenv("HOME") + "/.android/han1meviewer-release.jks"))
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    splits {
        abi {
            isEnable = (gradle.startParameter.taskRequests.toString().contains("Release"))
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_han1me_client"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_han1me_client_round"

        }

        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_han1me_client_debug"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_han1me_client_debug_round"
        }
    }
    buildFeatures {
        //noinspection DataBindingWithoutKapt
        dataBinding = true
        buildConfig = true
        viewBinding = true
        compose  =  true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        disable += setOf("EnsureInitializerMetadata")
    }
    namespace = "com.yenaly.han1meviewer"
}

kotlin {
    compilerOptions {
        jvmTarget.value(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-jvm-default=enable"
        )
    }
}


androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->

            //  val apkName = "你的应用名_V${output.versionName.get()}_Build${output.versionCode.get()}_${variant.buildType}.apk"
            val apkName = "Han1meClient-v${output.versionName.get()}.apk"
            (output as VariantOutputImpl).outputFileName = apkName
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.java)
    implementation(project(":yenaly_libs"))
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.material.icons.extended)
    // android related

    implementation(libs.bundles.android.base)
    implementation(libs.bundles.android.jetpack)
    implementation(libs.palette)
    implementation(libs.material)
    //compose
    implementation(platform(libs.compose.compose.bom))
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.ui.tooling.preview)
    implementation(libs.androidx.ui)
    androidTestImplementation(platform(libs.compose.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.compose.ui.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.compose.avatar.cropper)
    // datetime

    implementation(libs.datetime)

    // parse

    implementation(libs.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    // network

    implementation(libs.retrofit)
    implementation(libs.converter.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dns.over.https)

    // pic

    implementation(libs.coil)


    // video

    implementation(libs.jiaozi.video.player)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.mpv.lib)

    // view

    implementation(libs.multitype)
    implementation(libs.base.recyclerview.adapter.helper4)
    implementation(libs.expandable.textview)
    implementation(libs.spannable.x)
    implementation(libs.about)
    implementation(libs.circular.reveal.switch)
    implementation(libs.drawerlayout)

    // firebase

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.config)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.firebase.database)
    ksp(libs.room.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)

    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.espresso.core)

    // debugImplementation(libs.leak.canary)
}


// Private NAS URLs and encrypted metadata must not enter automatic Firebase network traces.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.excludes.add("com/yenaly/han1meviewer/logic/remote/**")
    }
}
