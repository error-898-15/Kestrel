import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// The CloudStream library (built from the pinned commit into local-m2/) and its
// transitive deps (kotlin-stdlib 2.4.x, kotlin-reflect 2.4.0, kotlinx-serialization 1.11,
// NiceHttp, quickjs) are compiled with Kotlin metadata 2.3/2.4, but this project uses
// Kotlin 2.1.10 (metadata 2.1). Without this flag :plugin-host:kspDebugKotlin fails with
// "Module was compiled with an incompatible version of Kotlin".
// KspTaskJvm extends KotlinCompile, so this covers KSP tasks too.
subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
