plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.limz26"
version = "0.1.0"

repositories {
    // 国内镜像（优先）
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/central")
    maven("https://maven.aliyun.com/repository/google")

    // 官方源（兜底）
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
}

intellij {
    version.set("2024.1")
    type.set("PC")

    plugins.set(listOf())
    instrumentCode.set(false)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }


    // Workaround: avoid flaky online self-version check in restricted networks
    withType<org.jetbrains.intellij.tasks.InitializeIntelliJPluginTask> {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("251.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
