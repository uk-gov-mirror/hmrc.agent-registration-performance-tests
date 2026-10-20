lazy val root = (project in file("."))
  .enablePlugins(GatlingPlugin)
  .settings(
    name := "agent-registration-performance-tests",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.12",
    //implicitConversions & postfixOps are Gatling recommended -language settings
    scalacOptions ++= Seq("-feature", "-language:implicitConversions", "-language:postfixOps"),
    // Enabling sbt-auto-build plugin provides DefaultBuildSettings with default `testOptions` from `sbt-settings` plugin.
    // These testOptions are not compatible with `sbt gatling:test`. So we have to override testOptions here.
    Test / testOptions := Seq.empty,
    libraryDependencies ++= Dependencies.test
  )

lazy val prepareProvideDetailsConcurrencyData =
  taskKey[Unit]("Reset and seed provide-details concurrency data before Gatling")

lazy val prepareRiskOutcomeData =
  taskKey[Unit]("Seed deterministic risk-outcome data before Gatling")

lazy val prepareRiskingResultsFileData =
  taskKey[Unit]("Seed applications and combine their risking results into one large file (data prep for the results file processing test)")

lazy val preparePerformanceTestData =
  taskKey[Unit]("Prepare all deterministic performance test data before Gatling")

lazy val cleanupPerformanceTestData =
  taskKey[Unit](
    "Delete applications created by the performance-test seeders"
  )

lazy val refreshGeneratedTestResources =
  taskKey[Unit]("Refresh generated feeder files on the Gatling test classpath")

def systemProperty(name: String, jvmOptions: Seq[String]): Option[String] =
  sys.props.get(name).orElse {
    jvmOptions.collectFirst {
      case option if option.startsWith(s"-D$name=") => option.stripPrefix(s"-D$name=")
    }
  }

def resetIfRequested(frontendUrl: String, baseDir: File, log: sbt.util.Logger): Unit = {
  if (sys.env.getOrElse("RESET_BEFORE_SEED", "false").equalsIgnoreCase("true")) {
    val resetUrl = sys.env.getOrElse("RESET_URL", s"$frontendUrl/agent-registration/test-only/reset")
    val exitCode = scala.sys.process.Process(
      "scripts/reset_agent_registration_data.sh",
      baseDir,
      "RESET_URL" -> resetUrl
    ).!
    if (exitCode != 0) sys.error(s"reset_agent_registration_data.sh failed with exit code $exitCode")
    log.info("Agent-registration data reset complete")
  }
}

prepareProvideDetailsConcurrencyData := {
  val log = streams.value.log
  val jvmOptions = javaOptions.value
  val runLocal = systemProperty("runLocal", jvmOptions).forall(_ == "true")

  val backendUrl =
    if (runLocal) "http://localhost:22202"
    else sys.env.getOrElse("BACKEND_URL", "https://agent-registration.protected.mdtp")

  val frontendUrl =
    if (runLocal) "http://localhost:22201"
    else sys.env.getOrElse("FRONTEND_URL", "https://www.staging.tax.service.gov.uk")

  val stubsUrl =
    if (runLocal) "http://localhost:9099"
    else sys.env.getOrElse("STUBS_URL", "https://www.staging.tax.service.gov.uk/agents-external-stubs")

  resetIfRequested(frontendUrl, baseDirectory.value, log)

  val args = Seq(
    "--backend-url", backendUrl,
    "--frontend-url", frontendUrl,
    "--stubs-url", stubsUrl,
    "--apps", sys.env.getOrElse("PROVIDE_DETAILS_APPS", "5"),
    "--individuals", sys.env.getOrElse("INDIVIDUALS_PER_APP", "6"),
    "--application-seed-mode", sys.env.getOrElse("APPLICATION_SEED_MODE", "frontend-fast-forward"),
    "--fast-forward-section", sys.env.getOrElse("FAST_FORWARD_SECTION", "LlpPartnersAndOtherRelevantTaxAdvisers6"),
    "--output", sys.env.getOrElse("OUTPUT", "src/test/resources/data/provide-details-concurrency.csv")
  )

  log.info(s"Preparing provide-details contention data in Scala. runLocal=$runLocal")
  (Test / runner).value
    .run(
      "uk.gov.hmrc.perftests.mmtar.seeding.ProvideDetailsSeeder",
      (Test / fullClasspath).value.files,
      args,
      log
    )
    .get
}

prepareRiskOutcomeData := {
  val log = streams.value.log
  val jvmOptions = javaOptions.value
  val runLocal = systemProperty("runLocal", jvmOptions).forall(_ == "true")

  val runSmokeTest =
    systemProperty("perftest.runSmokeTest", jvmOptions)
      .exists(_.equalsIgnoreCase("true"))

  val riskOutcomePool =
    sys.env
      .get("RISK_OUTCOME_POOLS")
      .orElse(systemProperty("riskOutcome.pool", jvmOptions))
      .getOrElse(if (runSmokeTest) "smoke" else "load")

  val backendUrl =
    if (runLocal) "http://localhost:22202"
    else sys.env.getOrElse(
      "BACKEND_URL",
      "https://agent-registration.protected.mdtp"
    )

  val frontendUrl =
    if (runLocal) "http://localhost:22201"
    else sys.env.getOrElse(
      "FRONTEND_URL",
      "https://www.staging.tax.service.gov.uk"
    )

  val stubsUrl =
    if (runLocal) "http://localhost:9099"
    else sys.env.getOrElse(
      "STUBS_URL",
      "https://www.staging.tax.service.gov.uk"
    )

  resetIfRequested(frontendUrl, baseDirectory.value, log)

  val optionalArgs = Seq(
    "load-main-records" -> sys.env.get("RISK_OUTCOME_LOAD_MAIN_RECORDS"),
    "load-control-records" -> sys.env.get("RISK_OUTCOME_LOAD_CONTROL_RECORDS"),
    "load-scale-records" -> sys.env.get("RISK_OUTCOME_LOAD_SCALE_RECORDS")
  ).flatMap {
    case (name, value) =>
      value.filter(_.nonEmpty).toSeq.flatMap(v => Seq(s"--$name", v))
  }

  val args = Seq(
    "--backend-url", backendUrl,
    "--frontend-url", frontendUrl,
    "--stubs-url", stubsUrl,
    "--output-dir", sys.env.getOrElse(
      "RISK_OUTCOME_OUTPUT_DIR",
      "src/test/resources/data/risk-outcomes"
    ),
    "--pool", riskOutcomePool,
    "--total-peak-jps", sys.env.getOrElse(
      "RISK_OUTCOME_TOTAL_PEAK_JPS",
      systemProperty("riskOutcome.totalPeakJps", jvmOptions).getOrElse("0.1")
    ),
    "--rampup-minutes", sys.env.getOrElse(
      "RISK_OUTCOME_RAMPUP_MINUTES",
      systemProperty("riskOutcome.rampUpMinutes", jvmOptions).getOrElse("1")
    ),
    "--steady-minutes", sys.env.getOrElse(
      "RISK_OUTCOME_STEADY_MINUTES",
      systemProperty("riskOutcome.steadyMinutes", jvmOptions).getOrElse("8")
    ),
    "--rampdown-minutes", sys.env.getOrElse(
      "RISK_OUTCOME_RAMPDOWN_MINUTES",
      systemProperty("riskOutcome.rampDownMinutes", jvmOptions).getOrElse("1")
    ),
    "--buffer-percent", sys.env.getOrElse(
      "RISK_OUTCOME_BUFFER_PERCENT",
      "20"
    ),
    "--smoke-records", sys.env.getOrElse(
      "RISK_OUTCOME_SMOKE_RECORDS",
      systemProperty("riskOutcome.smokeRecords", jvmOptions).getOrElse("1")
    )
  ) ++ optionalArgs ++
    (if (
      sys.env
        .getOrElse("RISK_OUTCOME_DRY_RUN", "false")
        .equalsIgnoreCase("true")
    )
      Seq("--dry-run")
    else Seq.empty)

  log.info(
    s"Preparing risk-outcome performance data in Scala. " +
      s"runLocal=$runLocal, runSmokeTest=$runSmokeTest, pool=$riskOutcomePool"
  )

  (Test / runner).value
    .run(
      "uk.gov.hmrc.perftests.mmtar.seeding.RiskOutcomeSeeder",
      (Test / fullClasspath).value.files,
      args,
      log
    )
    .get
}

prepareRiskingResultsFileData := {
  val log = streams.value.log
  val jvmOptions = javaOptions.value
  val runLocal = systemProperty("runLocal", jvmOptions).forall(_ == "true")

  val skip =
    sys.env.get("RISKING_RESULTS_SKIP_SEED").exists(_.equalsIgnoreCase("true"))

  val testRunner = (Test / runner).value
  val testClasspath = (Test / fullClasspath).value.files
  if (skip) {
    log.info("Skipping risking results file data preparation (RISKING_RESULTS_SKIP_SEED=true)")
  } else {
    val backendUrl =
      if (runLocal) "http://localhost:22202"
      else sys.env.getOrElse("BACKEND_URL", "https://agent-registration.protected.mdtp")

    val frontendUrl =
      if (runLocal) "http://localhost:22201"
      else sys.env.getOrElse("FRONTEND_URL", "https://www.staging.tax.service.gov.uk")

    val stubsUrl =
      if (runLocal) "http://localhost:9099"
      else sys.env.getOrElse("STUBS_URL", "https://www.staging.tax.service.gov.uk")

    val records =
      sys.env
        .get("RISKING_RESULTS_RECORDS")
        .orElse(systemProperty("riskingResults.records", jvmOptions))
        .getOrElse("750")

    resetIfRequested(frontendUrl, baseDirectory.value, log)

    val args = Seq(
      "--backend-url", backendUrl,
      "--frontend-url", frontendUrl,
      "--stubs-url", stubsUrl,
      "--records", records,
      "--combine-batch-size", sys.env.getOrElse("RISKING_RESULTS_COMBINE_BATCH_SIZE", "10")
    ) ++ (
      if (sys.env.getOrElse("RISKING_RESULTS_DRY_RUN", "false").equalsIgnoreCase("true")) Seq("--dry-run")
      else Seq.empty
    )

    log.info(s"Preparing risking results file data in Scala. runLocal=$runLocal, records=$records")

    testRunner
      .run(
        "uk.gov.hmrc.perftests.mmtar.seeding.RiskingResultsFileSeeder",
        testClasspath,
        args,
        log
      )
      .get
  }
}

refreshGeneratedTestResources := {
  val log = streams.value.log
  val sourceData = (Test / resourceDirectory).value / "data"
  val targetData = (Test / classDirectory).value / "data"

  IO.copyDirectory(
    sourceData,
    targetData,
    overwrite = true,
    preserveLastModified = true
  )

  log.info(s"Refreshed generated Gatling feeder resources from $sourceData to $targetData")
}

preparePerformanceTestData := Def.sequential(
  prepareProvideDetailsConcurrencyData,
  prepareRiskOutcomeData,
  prepareRiskingResultsFileData,
  refreshGeneratedTestResources
).value

cleanupPerformanceTestData := {
  val log = streams.value.log
  val jvmOptions = javaOptions.value
  val runLocal = systemProperty("runLocal", jvmOptions).forall(_ == "true")

  val runSmokeTest =
    systemProperty("perftest.runSmokeTest", jvmOptions)
      .exists(_.equalsIgnoreCase("true"))

  val riskOutcomePool =
    sys.env
      .get("RISK_OUTCOME_POOLS")
      .orElse(systemProperty("riskOutcome.pool", jvmOptions))
      .getOrElse(if (runSmokeTest) "smoke" else "load")

  val backendUrl =
    if (runLocal) "http://localhost:22202"
    else sys.env.getOrElse(
      "BACKEND_URL",
      "https://agent-registration.protected.mdtp"
    )

  val args = Seq(
    "--backend-url", backendUrl
  )

  log.info(s"Cleaning performance-test data in Scala. runLocal=$runLocal")

  (Test / runner).value
    .run(
      "uk.gov.hmrc.perftests.mmtar.seeding.PerformanceTestDataCleanup",
      (Test / fullClasspath).value.files,
      args,
      log
    )
    .get
}

Gatling / test := (Gatling / test).dependsOn(preparePerformanceTestData).value
