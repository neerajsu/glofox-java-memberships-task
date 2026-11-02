plugins {
	java
	id("org.springframework.boot") version "4.0.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.glofox"
version = "0.0.1-SNAPSHOT"
description = "Glofox Memberships take-home exercise"

java {
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

repositories {
	mavenCentral()
}

val shedLockVersion = "7.10.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("net.javacrumbs.shedlock:shedlock-spring:$shedLockVersion")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:$shedLockVersion")
	runtimeOnly("com.h2database:h2")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val integrationTest by sourceSets.creating {
	compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
	runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val integrationTestTask = tasks.register<Test>("integrationTest") {
	description = "Runs database and application integration tests."
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	testClassesDirs = integrationTest.output.classesDirs
	classpath = integrationTest.runtimeClasspath
	shouldRunAfter(tasks.test)
}

tasks.check {
	dependsOn(integrationTestTask)
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.withType<JavaCompile> {
	options.release.set(21)
}
