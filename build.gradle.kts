import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import org.gradle.jvm.tasks.Jar

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
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
    // MCP server with Ktor (follow kotlin-sdk docs)
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-server-sse:3.2.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-server-cors:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.8.4")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("io.ktor:ktor-client-core-jvm:3.2.3")
    testImplementation("io.ktor:ktor-client-cio-jvm:3.2.3")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.8.4")
}

intellij {
    version.set("2024.1")
    type.set("PC")

    plugins.set(listOf())
    instrumentCode.set(false)
}

tasks {
    // Skip: avoid flaky online check
    withType<org.jetbrains.intellij.tasks.InitializeIntelliJPluginTask> {
        enabled = false
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }


    // IMPORTANT: users may install a single plugin .jar directly (without lib/ directory).
    // Embed runtime dependencies into the plugin jar so PluginClassLoader can resolve MCP/Ktor classes.
    withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            configurations.runtimeClasspath.get().map { zipTree(it) }
        })
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    // Ensure external runtime dependencies are copied into plugin sandbox/lib to avoid PluginClassLoader CNFEs.
    withType<PrepareSandboxTask> {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(configurations.runtimeClasspath) {
            into("${intellij.pluginName.get()}/lib")
        }
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
