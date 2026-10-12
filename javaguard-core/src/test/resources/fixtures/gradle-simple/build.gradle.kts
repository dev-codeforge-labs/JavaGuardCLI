plugins {
    java
}

group = "com.acme"
version = "1.0.0"

tasks.withType<JavaCompile> {
    options.release.set(17)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
