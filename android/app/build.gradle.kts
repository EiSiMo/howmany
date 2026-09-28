plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// The GeCo2 model the app counts with, as exported by model/export.py.
val modelAsset = "geco2-int8.onnx"

android {
    namespace = "run.moritz.howmany"
    compileSdk = 37

    defaultConfig {
        applicationId = "run.moritz.howmany"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MODEL_ASSET", "\"$modelAsset\"")
        // The model needs a modern 64-bit ARM phone; other ABIs would only bloat the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so it installs locally for measuring performance.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Lets people pick the app's language in the system settings, among those it translates.
    androidResources { generateLocaleConfig = true }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(21) }

// Shows why a test failed even in quiet builds, like the pre-commit hook's.
tasks.withType<Test>().configureEach {
    testLogging.quiet {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The open source licenses the app shows. Works it bundles outside of Gradle's dependencies, like
// the model and the font, are defined in config/.
aboutLibraries { collect { configPath = file("../config") } }

/** Copies files from model/data (not committed) into generated assets. */
abstract class CopyModelFiles : DefaultTask() {
    @get:InputFiles abstract val files: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        files.forEach { file ->
            check(file.exists()) {
                "$file missing, export the model with model/export.py"
            }
            file.copyTo(output.resolve(file.name))
        }
    }
}

val modelData = rootDir.resolve("../model/data")
val copyModel by
    tasks.registering(CopyModelFiles::class) {
        files.from(modelData.resolve(modelAsset))
    }
// FSC-147 test images the benchmark also counts, for comparing app and benchmark results.
val copySample by
    tasks.registering(CopyModelFiles::class) {
        files.from(
            modelData.resolve("images/2147.jpg"),
            modelData.resolve("images/5574.jpg"),
        )
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyModel,
            CopyModelFiles::outputDir,
        )
        variant.androidTest
            ?.sources
            ?.assets
            ?.addGeneratedSourceDirectory(copySample, CopyModelFiles::outputDir)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.core)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
