plugins {
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "9.4.1"
    id("xyz.jpenilla.run-velocity") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.2-SNAPSHOT") {
        isTransitive = false
    }
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    testImplementation("org.geysermc.floodgate:api:2.2.2-SNAPSHOT")

    implementation("net.dv8tion:JDA:6.4.0") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.12")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion("3.5.0-SNAPSHOT")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }
}
