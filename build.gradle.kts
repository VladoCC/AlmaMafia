plugins {
    application
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

application {
    mainClass.set("org.example.MainKt")
}

tasks.jar {
    manifest.attributes["Main-Class"] = "org.example.MainKt"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    // MongoDB Kotlin driver dependency
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.10.1")
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.2.0")
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("ch.qos.logback:logback-core:1.5.13")
    implementation("janino:janino:2.5.10")

    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
    //ksp(project)

    api("com.google.guava:guava:33.3.1-jre")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}