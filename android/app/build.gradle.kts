plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The GeCo2 model the app counts with, as exported by the benchmark.
val modelAsset = "geco2-int8.onnx"

android {
    namespace = "run.moritz.quantify"
    compileSdk = 37

    defaultConfig {
        applicationId = "run.moritz.quantify"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MODEL_ASSET", "\"$modelAsset\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so it installs locally for measuring performance.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(21) }

/** Copies files exported by the benchmark (not committed) into generated assets. */
abstract class CopyBenchmarkFiles : DefaultTask() {
    @get:InputFiles abstract val files: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        files.forEach { file ->
            check(file.exists()) {
                "$file missing, export it with benchmark/prototypes/prototype-3.py"
            }
            file.copyTo(output.resolve(file.name))
        }
    }
}

val benchmarkData = rootDir.resolve("../benchmark/data")
val copyModel by
    tasks.registering(CopyBenchmarkFiles::class) {
        files.from(benchmarkData.resolve(modelAsset))
    }
// FSC-147 test images the benchmark also counts, for comparing app and benchmark results.
val copySample by
    tasks.registering(CopyBenchmarkFiles::class) {
        files.from(
            benchmarkData.resolve("images/2147.jpg"),
            benchmarkData.resolve("images/5574.jpg"),
        )
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyModel,
            CopyBenchmarkFiles::outputDir,
        )
        variant.androidTest
            ?.sources
            ?.assets
            ?.addGeneratedSourceDirectory(copySample, CopyBenchmarkFiles::outputDir)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
