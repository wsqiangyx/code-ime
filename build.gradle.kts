plugins {
    id("java")
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.codeime"
version = "1.4.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // JNA 由 IDE 自带（lib/ 下），只参与编译、不打进插件包，避免类加载冲突
    compileOnly("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation("net.java.dev.jna:jna-platform:5.17.0")
    // 探针（immProbe）独立于 IDE 运行，需要显式带上 stdlib
    testImplementation(kotlin("stdlib"))

    intellijPlatform {
        // 对准本机安装的 IDEA，免下载 IDE 分发包
        local("D:/software/JetBrains/idea-2026.1.1.win")
    }
}

kotlin {
    compilerOptions {
        // 字节码目标：IDEA 2026.1 平台要求的最低 Java 版本
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

java {
    // 平台最低要求 Java 21；本机没有对应 JDK 时由 foojay 自动下载
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = "CodeIME"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
        }
    }

    publishing {
        // 令牌从 ~/.gradle/gradle.properties（intellijPublishToken=...）
        // 或环境变量 INTELLIJ_PUBLISH_TOKEN 读取，勿提交到仓库
        token = providers.gradleProperty("intellijPublishToken")
            .orElse(providers.environmentVariable("INTELLIJ_PUBLISH_TOKEN"))
            .orElse("")
    }
}

/** 独立验证 imm32 JNA 绑定：./gradlew immProbe */
tasks.register<JavaExec>("immProbe") {
    group = "verification"
    mainClass = "com.codeime.tools.ImmProbeKt"
    classpath = sourceSets["test"].runtimeClasspath
}

/** 独立验证 SendInput 原子注入（无害键 F15）：./gradlew sendInputProbe */
tasks.register<JavaExec>("sendInputProbe") {
    group = "verification"
    mainClass = "com.codeime.tools.SendInputProbeKt"
    classpath = sourceSets["test"].runtimeClasspath
}
