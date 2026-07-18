plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(17)
}

sourceSets {
  main {
    kotlin.srcDirs("../android/src/main/java")
  }
  test {
    kotlin.srcDirs("../android/src/test/java")
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}

tasks.test {
  useJUnit()
}
