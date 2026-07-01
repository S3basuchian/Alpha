plugins {
	id("alpha.java-application-conventions")
}

dependencies {
	implementation(project(":alpha-solver"))
	implementation(project(":alpha-commons"))

	implementation("commons-cli:commons-cli:1.3.1")

	val poiVersion = "4.1.1"
	implementation("org.apache.poi:poi:${poiVersion}")
	implementation("org.apache.poi:poi-ooxml:${poiVersion}")

	// Logging Implementation
	implementation("org.slf4j:slf4j-simple:1.7.32") {
		// Exclude the SLF4J API, because we already have it via `alpha.java-application-conventions`.
		exclude("org.slf4j", "slf4j-api")
	}
}

val main = "at.ac.tuwien.kr.alpha.Main"

application {
	mainClass.set(main)
}

tasks.create<Jar>("bundledJar") {
	dependsOn(":alpha-api:jar", ":alpha-commons:jar", ":alpha-core:jar", ":alpha-solver:jar")

	manifest {
		attributes["Main-Class"] = main
		attributes["Multi-Release"] = true
	}

	with(tasks["jar"] as CopySpec)

	from(configurations.runtimeClasspath.get().map({ if (it.isDirectory()) it else zipTree(it) }))

	archiveFileName.set("${project.name}-${project.version}-bundled.jar")

	exclude("META-INF/DEPENDENCIES")
	
	/*
	 * In order to make sure we don"t overwrite NOTICE and LICENSE files coming from dependency
	 * jars with each other, number them while copying
	 */
	var noticeCount = 0
	rename { it : String ->
		return@rename if ("NOTICE.txt".equals(it) || "NOTICE".equals(it)) {
			noticeCount++
			"NOTICE.${noticeCount}.txt"
		} else {
			it
		}
	}

	var licenseCount = 0
	rename { it : String ->
		return@rename if ("LICENSE.txt".equals(it) || "LICENSE".equals(it)) {
			licenseCount++
			"LICENSE.${licenseCount}.txt"
		} else {
			it
		}
	}

	duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.test {
	useJUnitPlatform()
}

tasks.register<JavaExec>("runIncrementalExample") {
	group = "examples"
	description = "Run the AlphaSession incremental solving example (see examples/incremental.md)."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalSolvingExample")
}

tasks.register<JavaExec>("runIncrementalCutedgeBenchmark") {
	group = "examples"
	description = "Run the incremental cutedge benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalCutedgeBenchmark")
}

tasks.register<JavaExec>("runIncrementalCutedgeRetractionBenchmark") {
	group = "examples"
	description = "Cutedge iterative edge-cutting benchmark: solve, retract the AS-chosen edge, re-solve (session vs batch)."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalCutedgeRetractionBenchmark")
}

tasks.register<JavaExec>("runIncrementalHanoiBenchmark") {
	group = "examples"
	description = "Run the iterative-deepening Hanoi benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalHanoiBenchmark")
}

tasks.register<JavaExec>("runIncrementalReachBenchmark") {
	group = "examples"
	description = "Run the incremental reach benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalReachBenchmark")
}

tasks.register<JavaExec>("runIncrementalReachRetractionBenchmark") {
	group = "examples"
	description = "Run the reach benchmark with sliding-window retraction (session vs batch baseline)."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalReachRetractionBenchmark")
}

tasks.register<JavaExec>("runIncrementalSameGenBenchmark") {
	group = "examples"
	description = "Run the incremental same-generation benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalSameGenBenchmark")
}

tasks.register<JavaExec>("runIncrementalRdfsBenchmark") {
	group = "examples"
	description = "Run the incremental RDFS subclass + type benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalRdfsBenchmark")
}

tasks.register<JavaExec>("runIncrementalRdfsRetractionBenchmark") {
	group = "examples"
	description = "RDFS benchmark with sliding-window fact retraction (session vs batch)."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalRdfsRetractionBenchmark")
}

tasks.register<JavaExec>("runIncrementalLocstratBenchmark") {
	group = "examples"
	description = "Run the incremental locstrat benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalLocstratBenchmark")
}

tasks.register<JavaExec>("runIncrementalGroundExplosionBenchmark") {
	group = "examples"
	description = "Run the incremental Ground Explosion benchmark using AlphaSession."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalGroundExplosionBenchmark")
}

tasks.register<JavaExec>("runIncrementalGroundExplosionRetractionBenchmark") {
	group = "examples"
	description = "Ground-explosion benchmark with sliding-window dom retraction (session vs batch)."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalGroundExplosionRetractionBenchmark")
}

tasks.register<JavaExec>("runIncrementalGroundExplosionConstraintBenchmark") {
	group = "examples"
	description = "Ground-explosion benchmark with a fixed dom universe and streamed forbidding constraints (session vs batch)."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalGroundExplosionConstraintBenchmark")
}

tasks.register<JavaExec>("runIncrementalColoringConstraintBenchmark") {
	group = "examples"
	description = "Incremental 5-colorability with streamed colour-exclusion constraints (live vs batch); search-hard feasibility probe."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalColoringConstraintBenchmark")
}

tasks.register<JavaExec>("runIncrementalColoringGrowthBenchmark") {
	group = "examples"
	description = "Incremental 5-colorability under graph growth (+1 pendant vertex/edge per shot); live vs batch."
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("at.ac.tuwien.kr.alpha.app.examples.IncrementalColoringGrowthBenchmark")
}
