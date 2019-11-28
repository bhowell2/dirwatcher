import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent


buildscript {
  repositories {
    mavenCentral()
    //Needed only for SNAPSHOT versions
    //maven { url "http://oss.sonatype.org/content/repositories/snapshots/" }
  }
  dependencies {
    // requires gradle 5.x, cant be used with gradle 6 until it's updated. (not using now anyway)
//    classpath("io.codearte.gradle.nexus:gradle-nexus-staging-plugin:0.21.1")
  }
}

plugins {
  `java-library`
  jacoco
  `maven-publish`
  signing
//  id("io.codearte.nexus-staging") version("0.21.1")
}

version = "0.1.0"
group = "io.github.bhowell2"

repositories {
  jcenter()
  mavenCentral()
}

dependencies {
  implementation("org.slf4j:slf4j-api:1.7.28")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0-M1")
  testImplementation("io.github.bhowell2:async-test-helper:1.2.0")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0-M1")
}

val test by tasks.getting(Test::class) {
  // Use junit platform for unit tests
  testLogging {
    showStandardStreams = true
    events.add(TestLogEvent.FAILED)
    exceptionFormat = TestExceptionFormat.SHORT
  }
  useJUnitPlatform()
}

tasks.jacocoTestReport {
  reports {
    xml.setEnabled(true)
    html.setEnabled(false)
  }
}

tasks.check {
  dependsOn.add(tasks.jacocoTestReport)
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
//  withJavadocJar()
//  withSourcesJar()
}

tasks.register<Jar>("sourcesJar") {
  from(sourceSets.main.get().allJava)
  archiveClassifier.set("sources")
}

tasks.register<Jar>("javadocJar") {
  from(tasks.javadoc)
  archiveClassifier.set("javadoc")
}

// set by environment variables: ORG_GRADLE_PROJECT_sonatypeUsername, ORG_GRADLE_PROJECT_sonatypePassword
val sonatypeUsername: String? by project
val sonatypePassword: String? by project


publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = project.name
      from(components["java"])
      artifact(tasks["sourcesJar"])
      artifact(tasks["javadocJar"])
      versionMapping {
        usage("java-api") {
          fromResolutionOf("runtimeClasspath")
        }
        usage("java-runtime") {
          fromResolutionResult()
        }
      }
      pom {
        name.set("dirwatcher")
        description.set("Java directory watcher.")
        url.set("https://github.com/bhowell2/dirwatcher")
//        properties.set(mapOf(
//          "myProp" to "value",
//          "prop.with.dots" to "anotherValue"
//        ))
        licenses {
          license {
            name.set("MIT License")
            url.set("https://choosealicense.com/licenses/mit/")
          }
        }
        developers {
          developer {
            id.set("bhowell2")
            name.set("Blake Howell")
            email.set("bhowell2.github.io@gmail.com")
          }
        }
        scm {
          connection.set("scm:git:git://github.com:bhowell2/dirwatcher.git")
          developerConnection.set("scm:git:ssh://github.com:bhowell2/dirwatcher.git")
          url.set("https://github.com/bhowell2/dirwatcher")
        }
      }
    }
  }
  repositories {
    maven {
      val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
      val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots")
      url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
      credentials {
        username = sonatypeUsername
        password = sonatypePassword
      }
    }
  }
}

signing {
  // Set by the environmentVariables: ORG_GRADLE_PROJECT_signingKey, ORG_GRADLE_PROJECT_signingKeyPassword
  val signingKey: String? by project
  val signingKeyPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingKeyPassword)
  sign(publishing.publications["mavenJava"])
}

//nexusStaging {
//  username = sonatypeUsername
//  password = sonatypePassword
//  stagingProfileId = "84eece2cca5792"
//}
//

