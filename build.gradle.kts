plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

repositories {
    mavenCentral()
}

// gplayapi 4.0.0 chỉ phát hành dạng AAR (thư viện Android). Phần lõi là Kotlin/JVM thuần, nên
// tải AAR từ Maven Central rồi tách classes.jar ra dùng như một thư viện JVM bình thường.
val gplayAar: Configuration by configurations.creating { isTransitive = false }

val extractGplayApi by tasks.registering(Copy::class) {
    from({ zipTree(gplayAar.singleFile) }) {
        include("classes.jar")
        rename { "gplayapi-4.0.0.jar" }
    }
    into(layout.buildDirectory.dir("gplayapi"))
}

dependencies {
    gplayAar("com.auroraoss:gplayapi:4.0.0@aar")
    implementation(files(layout.buildDirectory.file("gplayapi/gplayapi-4.0.0.jar")).builtBy(extractGplayApi))

    // Các dependency mà POM của gplayapi khai báo.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.protobuf:protobuf-javalite:4.34.1")

    val ktor = "3.2.3"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(21)
}
