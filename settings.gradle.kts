pluginManagement {
    repositories {
        // 国内镜像（优先）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")

        // 官方源（兜底）
        gradlePluginPortal()
        mavenCentral()
    }
}
