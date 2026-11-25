import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application") version "9.3.1"
}

android {
    namespace = "io.github.vquand.anddiary"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "io.github.vquand.anddiary"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk.abiFilters += "arm64-v8a"
    }

    buildTypes.getByName("debug") {
        applicationIdSuffix = ".debug"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val ink = "1.1.0-alpha12"
    implementation("androidx.ink:ink-authoring:$ink")
    implementation("androidx.ink:ink-brush:$ink")
    implementation("androidx.ink:ink-rendering:$ink")
    implementation("androidx.ink:ink-storage:$ink")
    implementation("androidx.ink:ink-strokes:$ink")
}

abstract class BuildRustLibrary @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artwork: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cargoFiles: ConfigurableFileCollection

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:LocalState
    abstract val cargoTarget: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibraries: DirectoryProperty

    @TaskAction
    fun build() {
        val abi = "aarch64-linux-android"
        val toolchain = ndkDirectory.dir("toolchains/llvm/prebuilt/linux-x86_64/bin").get().asFile
        val clang = toolchain.resolve("${abi}26-clang")
        check(clang.canExecute()) { "Android NDK Linux x86-64 toolchain not found at $clang" }
        val target = cargoTarget.get().asFile

        exec.exec {
            workingDir(sources.get().asFile.parentFile)
            environment("CARGO_TARGET_DIR", target)
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
            environment("CC_aarch64_linux_android", clang)
            environment("AR_aarch64_linux_android", toolchain.resolve("llvm-ar"))
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
            commandLine("cargo", "build", "--locked", "--release", "--target", abi)
        }

        target.resolve("$abi/release/liband_diary.so").copyTo(
            jniLibraries.file("arm64-v8a/liband_diary.so").get().asFile,
            overwrite = true,
        )
    }
}

val buildRustLibrary = tasks.register<BuildRustLibrary>("buildRustLibrary") {
    sources.set(rootProject.layout.projectDirectory.dir("src"))
    artwork.set(rootProject.layout.projectDirectory.dir("assets"))
    cargoFiles.from(rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    ndkVersion.set(android.ndkVersion)
    cargoTarget.set(rootProject.layout.projectDirectory.dir("target"))
    jniLibraries.set(layout.buildDirectory.dir("generated/rustJniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustLibrary, BuildRustLibrary::jniLibraries)
}
