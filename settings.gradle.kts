pluginManagement {
    repositories {
        // 官方源优先(稳定可靠)
        google()
        mavenCentral()
        gradlePluginPortal()
        // 阿里云镜像作为备份(加速国内访问,任一挂掉时回落到上方官方源)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // 官方源优先
        google()
        mavenCentral()
        // Mozilla Maven(GeckoView 引擎)
        maven { url = uri("https://maven.mozilla.org/maven2/") }
        // 阿里云镜像作为备份
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

rootProject.name = "CctvOfficialNavigator"
include(":app")
