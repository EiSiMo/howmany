import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// The GeCo2 model the app counts with, as exported by model/export.py and published as a release
// asset, so building needs no export. A local export in model/data takes precedence; after a new
// export, publish it as the next model-v<N> release and update the URL and checksum.
val modelAsset = "geco2-int8.onnx"
val publishedModelUrl = "https://github.com/EiSiMo/howmany/releases/download/model-v1/$modelAsset"
val publishedModelSha256 = "d4ca4eb15fd01c58ef993c100eee41883ceb6c766c4b71d472238d4174f9a3ec"

// Signing keys, from .env at the project root or the environment (see .env.example). Without them,
// release builds stay unsigned, as F-Droid builds them before adding the published signature.
val dotEnv: Map<String, String> =
    rootDir
        .resolve("../.env")
        .takeIf { it.exists() }
        ?.readLines()
        .orEmpty()
        .filter { "=" in it && !it.trimStart().startsWith("#") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

fun secret(key: String): String? = System.getenv(key) ?: dotEnv[key]

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

    signingConfigs {
        // The app signing key signs what users install, from GitHub, F-Droid and Play alike; Play
        // holds a copy. The upload key only signs what is uploaded to Play.
        for (key in listOf("signing", "upload")) {
            val keystore = secret("${key.uppercase()}_KEYSTORE") ?: continue
            create(key) {
                storeFile = file(keystore)
                storePassword = secret("${key.uppercase()}_PASSWORD")
                keyAlias = key
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("signing")
        }
        // The release for uploading to Play, which signs it with the app signing key.
        create("play") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("upload")
            matchingFallbacks += "release"
        }
    }

    // Lets people pick the app's language in the system settings, among those it translates.
    // Keeps only those languages of the libraries, so the app never mixes in a language it lacks.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "de", "es", "fr", "pt", "ru", "b+zh+Hans")
    }

    // Only 64-bit ARM on purpose, see abiFilters.
    lint { disable += "ChromeOsAbiSupport" }

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

/** Downloads a file and fails unless it has the expected SHA-256 checksum. */
abstract class DownloadFile : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:OutputFile abstract val file: RegularFileProperty

    @TaskAction
    fun download() {
        val target = file.get().asFile
        val partial = target.resolveSibling("${target.name}.part")
        URI(url.get()).toURL().openStream().use { input ->
            partial.outputStream().use { input.copyTo(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) {
            partial.delete()
            "${url.get()} has SHA-256 $actual, expected ${sha256.get()}"
        }
        check(partial.renameTo(target)) { "Could not move $partial to $target" }
    }
}

/** Copies files, like the model and sample images, into generated assets. */
abstract class CopyModelFiles : DefaultTask() {
    @get:InputFiles abstract val files: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        files.forEach { file ->
            check(file.exists()) { "$file missing" }
            file.copyTo(output.resolve(file.name))
        }
    }
}

val modelData = rootDir.resolve("../model/data")
val downloadModel by
    tasks.registering(DownloadFile::class) {
        url = publishedModelUrl
        sha256 = publishedModelSha256
        file = layout.buildDirectory.file("downloads/$modelAsset")
    }
val localModel = modelData.resolve(modelAsset)
val copyModel by
    tasks.registering(CopyModelFiles::class) {
        files.from(if (localModel.exists()) localModel else downloadModel)
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
