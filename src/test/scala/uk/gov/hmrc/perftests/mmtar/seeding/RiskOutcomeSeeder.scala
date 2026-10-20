/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.perftests.mmtar.seeding

import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.math.ceil
import scala.util.matching.Regex
import ujson.{Arr, Obj}
import SeederSupport._

object RiskOutcomeSeeder {

  private val ApplicantResubmittedMarker = "You have resubmitted your application for an agent services account"
  private val IndividualConfirmationMarker = "You have finished this process"
  private val ApplicantTaskListMarker = "Take action"
  private val FastForwardIdPattern: Regex = "/agent-registration/test-only/show-agent-application-tile/([^/?#]+)".r

  final case class ScenarioDefinition(
                                       scenarioId: String,
                                       kind: String,
                                       businessType: String,
                                       completedSectionSlug: String,
                                       linkedIndividuals: Int,
                                       profile: String,
                                       expectedOutcome: String,
                                       entityFailures: Seq[String] = Seq.empty,
                                       individualFailuresByIndex: Map[Int, Seq[String]] = Map.empty,
                                       requiresInitialSubmission: Boolean = false,
                                       postSeedAction: String = "none",
                                       weight: Int = 1
                                     )

  final case class Config(
                           backendUrl: String,
                           frontendUrl: String,
                           stubsUrl: String,
                           outputDir: String,
                           pool: String,
                           totalPeakJps: Double,
                           rampupMinutes: Int,
                           steadyMinutes: Int,
                           rampdownMinutes: Int,
                           bufferPercent: Int,
                           loadMainRecords: Option[Int],
                           loadControlRecords: Option[Int],
                           loadScaleRecords: Option[Int],
                           smokeRecords: Int,
                           cleanupManifest: String,
                           dryRun: Boolean
                         )

  final case class ApplicationSeed(
                                    scenario: ScenarioDefinition,
                                    pool: String,
                                    recordIndex: Int,
                                    appId: String,
                                    applicationReference: String,
                                    linkId: String,
                                    applicantUserId: String,
                                    applicantPlanetId: String,
                                    individuals: Seq[Obj]
                                  )

  val scenarios: Seq[ScenarioDefinition] = Seq(
    ScenarioDefinition("APP-ST-FIX-RESUB", "applicant", "sole-trader", "sole-trader-declaration", 1, "main", "failed-fixable-resubmission", individualFailuresByIndex = Map(0 -> Seq("Check_4_1", "Check_10_1")), weight = 8),
    ScenarioDefinition("APP-LTD-FIX-RESUB-2", "applicant", "limited-company", "limited-company-declaration", 2, "main", "failed-fixable-resubmission", entityFailures = Seq("Check_4_1"), weight = 6),
    ScenarioDefinition("APP-LLP-FIX-RESUB-2", "applicant", "llp", "llp-declaration", 2, "main", "failed-fixable-resubmission", entityFailures = Seq("Check_4_1"), weight = 6),
    ScenarioDefinition("APP-GP-FIX-RESUB-2", "applicant", "general-partnership", "general-partnership-declaration", 2, "main", "failed-fixable-resubmission", entityFailures = Seq("Check_4_1"), weight = 5),
    ScenarioDefinition("APP-SP-FIX-RESUB-2", "applicant", "scottish-partnership", "scottish-partnership-declaration", 2, "main", "failed-fixable-resubmission", entityFailures = Seq("Check_4_1"), weight = 5),
    ScenarioDefinition("APP-LP-FIX-RESUB-2", "applicant", "limited-partnership", "limited-partnership-declaration", 2, "main", "failed-fixable-resubmission", entityFailures = Seq("Check_4_1"), weight = 5),
    ScenarioDefinition("APP-SLP-FIX-RESUB-2", "applicant", "scottish-limited-partnership", "scottish-limited-partnership-declaration", 2, "main", "failed-fixable-resubmission", entityFailures = Seq("Check_4_1"), weight = 5),
    ScenarioDefinition("APP-AMLS-FIX-RESUB", "applicant", "limited-company", "limited-company-declaration", 2, "main", "failed-fixable-amls-resubmission", entityFailures = Seq("Check_3_1"), weight = 4),
    ScenarioDefinition("APP-RESUBMITTED-STATUS", "applicant", "llp", "llp-declaration", 2, "control", "already-resubmitted-status", entityFailures = Seq("Check_4_1"), postSeedAction = "applicant_resubmit"),
    ScenarioDefinition("IND-FIX-DETAILS", "individual", "llp", "llp-declaration", 2, "main", "failed-fixable-details", individualFailuresByIndex = Map(0 -> Seq("Check_4_1", "Check_10_1")), weight = 8),
    ScenarioDefinition("IND-FIX-CONFIRM-ONLY", "individual", "limited-company", "limited-company-declaration", 2, "main", "failed-fixable-confirm-only", individualFailuresByIndex = Map(0 -> Seq("Check_4_1")), weight = 6),
    ScenarioDefinition("IND-FIX-ALREADY-CONFIRMED", "individual", "llp", "llp-declaration", 2, "control", "failed-fixable-already-confirmed", individualFailuresByIndex = Map(0 -> Seq("Check_4_1")), postSeedAction = "individual_complete"),
    ScenarioDefinition("IND-NONFIXABLE-CONTROL", "individual", "limited-company", "limited-company-declaration", 2, "control", "failed-non-fixable-control", individualFailuresByIndex = Map(0 -> Seq("Check_6"))),
    ScenarioDefinition("IND-APPROVED-CONTROL", "individual", "llp", "llp-declaration", 2, "control", "approved-control"),
    ScenarioDefinition("APP-LTD-FIX-RESUB-6", "applicant", "limited-company", "limited-company-partners-and-other-relevant-tax-advisers6", 6, "scale", "failed-fixable-resubmission-scale", entityFailures = Seq("Check_4_1"), requiresInitialSubmission = true, weight = 3),
    ScenarioDefinition("APP-LLP-FIX-RESUB-6", "applicant", "llp", "llp-partners-and-other-relevant-tax-advisers6", 6, "scale", "failed-fixable-resubmission-scale", entityFailures = Seq("Check_4_1"), requiresInitialSubmission = true, weight = 3),
    ScenarioDefinition("APP-GP-FIX-RESUB-6", "applicant", "general-partnership", "general-partnership-partners-and-other-relevant-tax-advisers6", 6, "scale", "failed-fixable-resubmission-scale", entityFailures = Seq("Check_4_1"), requiresInitialSubmission = true, weight = 2)
  )

  private val applicantHeaders = Seq(
    "scenario_id", "business_type", "application_reference", "applicant_login_url", "applicant_start_url", "expected_outcome",
    "task_list_url", "status_url", "declaration_url", "entity_failure_url", "entity_failure_code", "individual_failure_url",
    "individual_failure_code", "individual_identity_url", "individual_dob_url", "individual_nino_url", "individual_sautr_url",
    "individual_check_your_answers_url", "amls_failure_url", "amls_failure_code", "amls_supervisor_url", "amls_registration_url",
    "amls_evidence_url", "amls_check_your_answers_url", "parent_individual_count"
  )

  private val individualHeaders = Seq(
    "scenario_id", "business_type", "application_reference", "person_reference", "link_id", "individual_name", "individual_login_url",
    "individual_start_url", "expected_outcome", "task_list_url", "failure_details_url", "failure_code", "identity_url", "dob_url",
    "nino_url", "sautr_url", "check_your_answers_url", "declaration_url", "confirmation_url", "parent_individual_count"
  )

  def main(args: Array[String]): Unit = {
    val parsed = parseArgs(args)
    val pool = option(parsed, "pool", "load")

    require(
      Set("smoke", "load").contains(pool),
      s"Unsupported pool: $pool"
    )

    val config = Config(
      backendUrl = option(parsed, "backend-url", "http://localhost:22202").stripSuffix("/"),
      frontendUrl = option(parsed, "frontend-url", "http://localhost:22201").stripSuffix("/"),
      stubsUrl = option(parsed, "stubs-url", "http://localhost:9099").stripSuffix("/"),
      outputDir = option(parsed, "output-dir", "src/test/resources/data/risk-outcomes"),
      pool = pool,
      totalPeakJps = doubleOption(parsed, "total-peak-jps", 0.1),
      rampupMinutes = intOption(parsed, "rampup-minutes", 1),
      steadyMinutes = intOption(parsed, "steady-minutes", 8),
      rampdownMinutes = intOption(parsed, "rampdown-minutes", 1),
      bufferPercent = intOption(parsed, "buffer-percent", 20),
      loadMainRecords = optionalInt(parsed, "load-main-records"),
      loadControlRecords = optionalInt(parsed, "load-control-records"),
      loadScaleRecords = optionalInt(parsed, "load-scale-records"),
      smokeRecords = intOption(parsed, "smoke-records", 1),
      cleanupManifest = option(
        parsed,
        "cleanup-manifest",
        "tmp/performance-test-cleanup/risk-outcomes.json"
      ),
      dryRun = parsed.get("dry-run").contains("true")
    )

    println("\nRisk-outcome deterministic seeding")
    println(s"  Backend      : ${config.backendUrl}")
    println(s"  Frontend     : ${config.frontendUrl}")
    println(s"  Stubs        : ${config.stubsUrl}")
    println(s"  Output dir   : ${config.outputDir}")
    println(s"  Cleanup      : ${config.cleanupManifest}")
    println(s"  Pool         : ${config.pool}")
    println(s"  Peak JPS     : ${config.totalPeakJps}")
    println(s"  Profile      : ${config.rampupMinutes}m ramp-up, ${config.steadyMinutes}m steady, ${config.rampdownMinutes}m ramp-down")
    println(s"  Buffer       : ${config.bufferPercent}%")

    scenarios.foreach { scenario =>
      val count = recordsForPool(scenario, config.pool, config)
      println(f"  ${scenario.scenarioId}%-28s ${config.pool}=$count")
    }

    if (config.dryRun) {
      println("\nDry run complete. No API calls made.")
      return
    }

    val seeder = new Seeder(config)

    requireSuccess(
      seeder.http.get(
        s"${config.backendUrl}/agent-registration/test-only/recent-applications"
      ),
      "Backend readiness check"
    )

    println(s"\n=== Generating pool: ${config.pool} ===")

    val outputDir = Paths.get(config.outputDir)
    Files.createDirectories(outputDir)

    val applicantRowsByScenario =
      mutable.Map.empty[String, mutable.ArrayBuffer[Map[String, String]]]
        .withDefaultValue(mutable.ArrayBuffer.empty)

    val individualRowsByScenario =
      mutable.Map.empty[String, mutable.ArrayBuffer[Map[String, String]]]
        .withDefaultValue(mutable.ArrayBuffer.empty)

    val applicantAggregate = mutable.ArrayBuffer.empty[Map[String, String]]
    val individualAggregate = mutable.ArrayBuffer.empty[Map[String, String]]
    val controlsAggregate = mutable.ArrayBuffer.empty[Map[String, String]]
    val twoPersonAggregate = mutable.ArrayBuffer.empty[Map[String, String]]
    val sixPersonAggregate = mutable.ArrayBuffer.empty[Map[String, String]]
    val seeds = mutable.ArrayBuffer.empty[ApplicationSeed]

    scenarios.foreach { scenario =>
      val count = recordsForPool(scenario, config.pool, config)

      (1 to count).foreach { index =>
        println(s"  [${config.pool}] ${scenario.scenarioId} $index/$count")
        seeds += seeder.createApplicationSeed(
          scenario,
          config.pool,
          index
        )
      }
    }

    println("\n  Running risking for all newly created records...")
    seeder.runRisking()
    seeder.waitForRiskingInputs(seeds.toSeq)

    val maxBatchWeight = 6

    def seedWeight(seed: ApplicationSeed): Int =
      math.max(1, seed.scenario.linkedIndividuals)

    def weightedBatches(
                         seeds: Seq[ApplicationSeed],
                         maxWeight: Int
                       ): Seq[Seq[ApplicationSeed]] = {

      seeds.foldLeft(Vector(Vector.empty[ApplicationSeed])) { (batches, seed) =>
        val currentBatch = batches.last
        val currentWeight = currentBatch.map(seedWeight).sum
        val weight = seedWeight(seed)

        if (currentBatch.nonEmpty && currentWeight + weight > maxWeight) {
          batches :+ Vector(seed)
        } else {
          batches.init :+ (currentBatch :+ seed)
        }
      }.filter(_.nonEmpty)
    }

    def processBatch(batch: Seq[ApplicationSeed]): Unit = {
      val batchWeight = batch.map(seedWeight).sum

      println(
        s"  Uploading outcomes for ${batch.size} applications " +
          s"(weight=$batchWeight): " +
          batch.map(_.scenario.scenarioId).mkString(", ")
      )

      batch.foreach(seeder.uploadOutcomes)

      println(
        s"  Processing risk results for batch of ${batch.size} applications " +
          s"(weight=$batchWeight)..."
      )

      seeder.runResultsProcessing()
      seeder.waitForExpectedOutcomes(batch)
    }

    val batches =
      weightedBatches(seeds.toSeq, maxBatchWeight)

    println(
      s"  Uploading and processing deterministic outcomes in ${batches.size} weighted batches..."
    )

    batches.foreach(processBatch)

    println("  Building feeder rows...")

    seeds.foreach { seed =>
      val scenario = seed.scenario

      if (scenario.kind == "applicant") {
        val routes = applicantRoutes(seeder.frontend, seed.linkId)

        var row = Map(
          "scenario_id" -> scenario.scenarioId,
          "business_type" -> scenario.businessType,
          "application_reference" -> seed.applicationReference,
          "applicant_login_url" -> seeder.applicantLoginUrl(seed.appId, routes("task_list_url")),
          "applicant_start_url" -> (if (scenario.postSeedAction == "applicant_resubmit") routes("status_url") else routes("task_list_url")),
          "expected_outcome" -> scenario.expectedOutcome,
          "task_list_url" -> routes("task_list_url"),
          "status_url" -> routes("status_url"),
          "declaration_url" -> routes("declaration_url"),
          "entity_failure_url" -> (if (scenario.entityFailures.nonEmpty) routes("entity_failure_url") else ""),
          "entity_failure_code" -> (if (scenario.entityFailures.nonEmpty) "EntityFix.4.1" else ""),
          "individual_failure_url" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") routes("individual_failure_url") else ""),
          "individual_failure_code" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") "IndividualFix.4.1" else ""),
          "individual_identity_url" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") routes("individual_identity_url") else ""),
          "individual_dob_url" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") routes("individual_dob_url") else ""),
          "individual_nino_url" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") routes("individual_nino_url") else ""),
          "individual_sautr_url" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") routes("individual_sautr_url") else ""),
          "individual_check_your_answers_url" -> (if (scenario.scenarioId == "APP-ST-FIX-RESUB") routes("individual_check_your_answers_url") else ""),
          "amls_failure_url" -> (if (scenario.scenarioId == "APP-AMLS-FIX-RESUB") routes("amls_failure_url") else ""),
          "amls_failure_code" -> (if (scenario.scenarioId == "APP-AMLS-FIX-RESUB") "EntityFix.3.1" else ""),
          "amls_supervisor_url" -> (if (scenario.scenarioId == "APP-AMLS-FIX-RESUB") routes("amls_supervisor_url") else ""),
          "amls_registration_url" -> (if (scenario.scenarioId == "APP-AMLS-FIX-RESUB") routes("amls_registration_url") else ""),
          "amls_evidence_url" -> (if (scenario.scenarioId == "APP-AMLS-FIX-RESUB") routes("amls_evidence_url") else ""),
          "amls_check_your_answers_url" -> (if (scenario.scenarioId == "APP-AMLS-FIX-RESUB") routes("amls_check_your_answers_url") else ""),
          "parent_individual_count" -> scenario.linkedIndividuals.toString
        )

        if (scenario.postSeedAction == "applicant_resubmit") {
          seeder.completeApplicantResubmission(row)
          row = row
            .updated("applicant_login_url", seeder.applicantLoginUrl(seed.appId, routes("status_url")))
            .updated("applicant_start_url", routes("status_url"))
        }

        appendByScenario(applicantRowsByScenario, scenario.scenarioId, row)
        applicantAggregate += row
        addVariants(scenario, row, controlsAggregate, twoPersonAggregate, sixPersonAggregate)

      } else {
        val targetFailures = scenario.individualFailuresByIndex.getOrElse(0, Seq.empty)
        val individual = seed.individuals.head
        val individualId = field(individual, "_id")
        val personReference = field(individual, "personReference")
        val individualName = field(individual, "individualName")
        val failureCode = "IndividualFix.4.1"
        val routes = individualRoutes(seeder.frontend, seed.linkId, failureCode)
        val startUrl =
          if (scenario.scenarioId == "IND-FIX-ALREADY-CONFIRMED") routes("task_list_url")
          else routes("start_url")

        var row = Map(
          "scenario_id" -> scenario.scenarioId,
          "business_type" -> scenario.businessType,
          "application_reference" -> seed.applicationReference,
          "person_reference" -> personReference,
          "link_id" -> seed.linkId,
          "individual_name" -> individualName,
          "individual_login_url" -> seeder.individualLoginUrl(seed.appId, individualId, individualName, startUrl),
          "individual_start_url" -> startUrl,
          "expected_outcome" -> scenario.expectedOutcome,
          "task_list_url" -> routes("task_list_url"),
          "failure_details_url" -> (if (targetFailures.nonEmpty) routes("failure_details_url") else ""),
          "failure_code" -> (if (targetFailures.nonEmpty) failureCode else ""),
          "identity_url" -> (if (targetFailures.exists(_.startsWith("Check_10"))) routes("identity_url") else ""),
          "dob_url" -> (if (targetFailures.exists(_.startsWith("Check_10"))) routes("dob_url") else ""),
          "nino_url" -> (if (targetFailures.exists(_.startsWith("Check_10"))) routes("nino_url") else ""),
          "sautr_url" -> (if (targetFailures.exists(_.startsWith("Check_10"))) routes("sautr_url") else ""),
          "check_your_answers_url" -> (if (targetFailures.exists(_.startsWith("Check_10"))) routes("check_your_answers_url") else ""),
          "declaration_url" -> routes("declaration_url"),
          "confirmation_url" -> routes("confirmation_url"),
          "parent_individual_count" -> scenario.linkedIndividuals.toString
        )

        if (scenario.postSeedAction == "individual_complete") {
          seeder.completeIndividualFixableJourney(row)
          row = row
            .updated(
              "individual_login_url",
              seeder.individualLoginUrl(seed.appId, individualId, individualName, routes("task_list_url"))
            )
            .updated("individual_start_url", routes("task_list_url"))
        }

        appendByScenario(individualRowsByScenario, scenario.scenarioId, row)
        individualAggregate += row
        addVariants(scenario, row, controlsAggregate, twoPersonAggregate, sixPersonAggregate)
      }
    }

    scenarios.foreach { scenario =>
      val rows =
        if (scenario.kind == "applicant")
          applicantRowsByScenario.getOrElse(scenario.scenarioId, mutable.ArrayBuffer.empty)
        else
          individualRowsByScenario.getOrElse(scenario.scenarioId, mutable.ArrayBuffer.empty)

      val headers =
        if (scenario.kind == "applicant") applicantHeaders
        else individualHeaders

      writeCsv(
        outputDir.resolve(s"${scenario.scenarioId}.csv").toString,
        headers,
        rows.toSeq
      )
    }

    writeCsv(outputDir.resolve("applicant-scenarios.csv").toString, applicantHeaders, applicantAggregate.toSeq)
    writeCsv(outputDir.resolve("individual-scenarios.csv").toString, individualHeaders, individualAggregate.toSeq)
    writeCsv(outputDir.resolve("control-scenarios.csv").toString, supersetHeaders(controlsAggregate.toSeq), controlsAggregate.toSeq)
    writeCsv(outputDir.resolve("two-person-variants.csv").toString, supersetHeaders(twoPersonAggregate.toSeq), twoPersonAggregate.toSeq)
    writeCsv(outputDir.resolve("six-person-variants.csv").toString, supersetHeaders(sixPersonAggregate.toSeq), sixPersonAggregate.toSeq)

    val files = Files.list(outputDir)
    val fileNames =
      try files.iterator().asScala.map(_.getFileName.toString).toSeq.sorted
      finally files.close()

    val manifest = Obj(
      "pool" -> config.pool,
      "scenarioCounts" -> Obj.from(
        scenarios.map { scenario =>
          scenario.scenarioId ->
            ujson.Num(recordsForPool(scenario, config.pool, config))
        }
      ),
      "applicantRecords" -> applicantAggregate.size,
      "individualRecords" -> individualAggregate.size,
      "controlRecords" -> controlsAggregate.size,
      "twoPersonRecords" -> twoPersonAggregate.size,
      "sixPersonRecords" -> sixPersonAggregate.size,
      "files" -> Arr.from(fileNames.map(ujson.Str(_)))
    )

    writeJson(
      outputDir.resolve("manifest.json").toString,
      manifest
    )

    println("\nDone. Generated deterministic risk-outcome feeder data.")
  }

  private def appendByScenario(map: mutable.Map[String, mutable.ArrayBuffer[Map[String, String]]], key: String, row: Map[String, String]): Unit =
    map.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += row

  private def addVariants(
                           scenario: ScenarioDefinition,
                           row: Map[String, String],
                           controls: mutable.ArrayBuffer[Map[String, String]],
                           twoPerson: mutable.ArrayBuffer[Map[String, String]],
                           sixPerson: mutable.ArrayBuffer[Map[String, String]]
                         ): Unit = {
    if (scenario.profile == "control") controls += row
    if (scenario.linkedIndividuals == 2) twoPerson += row
    if (scenario.linkedIndividuals == 6) sixPerson += row
  }

  private def supersetHeaders(rows: Seq[Map[String, String]]): Seq[String] = rows.flatMap(_.keys).distinct.sorted

  private def effectiveSeconds(config: Config): Double =
    (config.rampupMinutes * 60.0 / 2) + (config.steadyMinutes * 60.0) + (config.rampdownMinutes * 60.0 / 2)

  private def loadRecordsFor(scenario: ScenarioDefinition, config: Config): Int = {
    val overrideValue = scenario.profile match {
      case "main" => config.loadMainRecords
      case "control" => config.loadControlRecords
      case "scale" => config.loadScaleRecords
    }
    overrideValue.getOrElse {
      val totalWeight = scenarios.map(_.weight).sum
      val peakJps = config.totalPeakJps * scenario.weight / totalWeight
      val buffered = peakJps * effectiveSeconds(config) * (1 + config.bufferPercent / 100.0)
      val minimum = scenario.profile match {
        case "main" => 2
        case "control" => 1
        case "scale" => 2
      }
      math.max(minimum, ceil(buffered).toInt)
    }
  }

  private def recordsForPool(scenario: ScenarioDefinition, pool: String, config: Config): Int = pool match {
    case "smoke" => math.max(1, config.smokeRecords)
    case "load" => loadRecordsFor(scenario, config)
  }

  private def applicantRoutes(frontend: String, linkId: String): Map[String, String] = {
    val base = s"$frontend/agent-registration"
    Map(
      "task_list_url" -> s"$base/conditions-not-yet-met/task-list",
      "status_url" -> s"$base/application-status",
      "declaration_url" -> s"$base/conditions-not-yet-met/declaration",
      "entity_failure_url" -> s"$base/conditions-not-yet-met/failure-details/EntityFix.4.1",
      "individual_failure_url" -> s"$base/conditions-not-yet-met/sole-trader-failure-details/IndividualFix.4.1",
      "individual_identity_url" -> s"$base/conditions-not-yet-met/sole-trader/identity",
      "individual_dob_url" -> s"$base/conditions-not-yet-met/sole-trader/date-of-birth",
      "individual_nino_url" -> s"$base/conditions-not-yet-met/sole-trader/national-insurance-number",
      "individual_sautr_url" -> s"$base/conditions-not-yet-met/sole-trader/self-assessment-unique-taxpayer-reference",
      "individual_check_your_answers_url" -> s"$base/conditions-not-yet-met/sole-trader/check-your-answers",
      "amls_failure_url" -> s"$base/conditions-not-yet-met/anti-money-laundering/failure-details/EntityFix.3.1",
      "amls_supervisor_url" -> s"$base/conditions-not-yet-met/anti-money-laundering/supervisor-name",
      "amls_registration_url" -> s"$base/conditions-not-yet-met/anti-money-laundering/registration-number",
      "amls_evidence_url" -> s"$base/conditions-not-yet-met/anti-money-laundering/evidence",
      "amls_check_your_answers_url" -> s"$base/conditions-not-yet-met/anti-money-laundering/check-your-answers",
      "individual_outcome_url" -> s"$base/provide-details/outcome-status/$linkId"
    )
  }

  private def individualRoutes(frontend: String, linkId: String, failureCode: String): Map[String, String] = {
    val base = s"$frontend/agent-registration"
    Map(
      "start_url" -> s"$base/provide-details/outcome-status/$linkId",
      "task_list_url" -> s"$base/provide-details/conditions-not-yet-met/task-list/$linkId",
      "failure_details_url" -> s"$base/provide-details/conditions-not-yet-met/failure-details/$failureCode/$linkId",
      "identity_url" -> s"$base/provide-details/conditions-not-yet-met/identity/$linkId",
      "dob_url" -> s"$base/provide-details/conditions-not-yet-met/date-of-birth/$linkId",
      "nino_url" -> s"$base/provide-details/conditions-not-yet-met/national-insurance-number/$linkId",
      "sautr_url" -> s"$base/provide-details/conditions-not-yet-met/self-assessment-unique-taxpayer-reference/$linkId",
      "check_your_answers_url" -> s"$base/provide-details/conditions-not-yet-met/check-your-answers/$linkId",
      "declaration_url" -> s"$base/provide-details/conditions-not-yet-met/declaration/$linkId",
      "confirmation_url" -> s"$base/provide-details/conditions-not-yet-met/confirmation/$linkId"
    )
  }

  final class Seeder(config: Config) {
    val frontend: String = config.frontendUrl
    val backend: String = config.backendUrl
    val stubs: String = config.stubsUrl
    val http = new SeederHttpClient(30, persistCookies = false)

    private var sequence = 0
    private val runId = System.currentTimeMillis().toString

    private val seededApplicationIds =
      mutable.ArrayBuffer.from(
        readCleanupManifest(config.cleanupManifest)
      )

    private def recordCreatedApplication(appId: String): Unit = {
      if (!seededApplicationIds.contains(appId)) {
        seededApplicationIds += appId

        writeCleanupManifest(
          config.cleanupManifest,
          seededApplicationIds.toSeq
        )

        println(s"      Recorded $appId for cleanup")
      }
    }

    private def nextFilename(prefix: String): String = {
      sequence += 1
      f"$prefix-$runId-$sequence%05d.json"
    }

    def applicantLoginUrl(appId: String, redirectUrl: String): String =
      s"$frontend/agent-registration/test-only/find-and-log-in-applicant/${urlEncode(s"applicant_$appId")}/${urlEncode(s"MMTAR_$appId")}?redirectUrl=${urlEncode(redirectUrl)}"

    def individualLoginUrl(appId: String, individualId: String, individualName: String, redirectUrl: String): String =
      s"$frontend/agent-registration/test-only/find-or-create-and-log-in-individual/${urlEncode(s"individual_$individualId")}/${urlEncode(s"MMTAR_$appId")}/${urlEncode(individualName)}?redirectUrl=${urlEncode(redirectUrl)}"

    private def fastForwardApplication(completedSectionSlug: String): String = {
      val url = s"$frontend/agent-registration/test-only/fast-forward-to/$completedSectionSlug"
      val response = requireRedirect(http.get(url), "Fast-forward application")
      val location = response.header("Location").getOrElse(throw new RuntimeException(s"Missing Location header from $url"))
      FastForwardIdPattern.findFirstMatchIn(location).map(_.group(1)).getOrElse(throw new RuntimeException(s"Could not extract agentApplicationId from fast-forward redirect: $location"))
    }

    private def findApplication(appId: String): Obj = {
      val response = requireSuccess(http.get(s"$backend/agent-registration/test-only/application/by-agent-application-id/$appId"), s"Find application $appId")
      ujson.read(response.body).obj
    }

    private def findIndividuals(appId: String): Seq[Obj] = {
      val response = requireSuccess(http.get(s"$backend/agent-registration/test-only/individuals/by-agent-application-id/$appId"), s"Find individuals for $appId")
      ujson.read(response.body).arr.map(value => Obj.from(value.obj)).toSeq
    }

    private def applicationReferenceExistsForRisking(ref: String): Boolean =
      !requireSuccess(http.get(s"$frontend/agent-registration/test-only/risking/application-for-risking/${urlEncode(ref)}"), "Check application-for-risking").body.contains("No application-for-risking found")

    private def personReferenceExistsForRisking(ref: String): Boolean =
      !requireSuccess(http.get(s"$frontend/agent-registration/test-only/risking/individual-for-risking/${urlEncode(ref)}"), "Check individual-for-risking").body.contains("No individual-for-risking found")

    private def applicationOutcomeType(app: Obj): String = app.value.get("riskingOutcomeApplication") match {
      case Some(obj: Obj) => field(obj, "outcome")
      case _ => ""
    }

    private def individualOutcomeType(ind: Obj): String = ind.value.get("riskingOutcomeIndividual") match {
      case Some(obj: Obj) => field(obj, "type")
      case _ => ""
    }

    private def expectedIndividualOutcomeType(codes: Seq[String]): String =
      if (codes.exists(_.startsWith("Check_6"))) "FailedNonFixable" else if (codes.nonEmpty) "FailedFixable" else "Approved"

    private def expectedApplicationOutcomeType(scenario: ScenarioDefinition): String =
      if (scenario.entityFailures.nonEmpty) expectedIndividualOutcomeType(scenario.entityFailures)
      else expectedIndividualOutcomeType(scenario.individualFailuresByIndex.values.flatten.toSeq)

    def waitForExpectedOutcomes(seeds: Seq[ApplicationSeed]): Unit = seeds.foreach { seed =>
      val expectedApp = expectedApplicationOutcomeType(seed.scenario)
      waitFor(s"application outcome ${seed.applicationReference} -> $expectedApp") {
        applicationOutcomeType(findApplication(seed.appId)) == expectedApp
      }
      seed.individuals.zipWithIndex.foreach { case (individual, index) =>
        val individualId = field(individual, "_id")
        val expected = expectedIndividualOutcomeType(seed.scenario.individualFailuresByIndex.getOrElse(index, Seq.empty))
        waitFor(s"individual outcome $individualId -> $expected") {
          findIndividuals(seed.appId).find(ind => field(ind, "_id") == individualId).exists(individualOutcomeType(_) == expected)
        }
      }
    }

    def runRisking(): Unit = requireSuccess(http.get(s"$frontend/agent-registration/test-only/risking/run"), "Run risking")
    def runResultsProcessing(): Unit = requireSuccess(http.get(s"$frontend/agent-registration/test-only/risking/run-results-file-processing"), "Run risking results processing")

    def combineAllUnprocessedResultsFiles(): Unit =
      requireRedirect(
        http.postEmpty(s"$frontend/agent-registration/test-only/risking/risking-results-files/combine-all-unprocessed"),
        "Combine all unprocessed risking results files"
      )

    private def submitEntityFailures(applicationReference: String, failures: Seq[String], fileName: String): Unit = {
      val url = s"$frontend/agent-registration/test-only/risking/select-entity-failures/${urlEncode(applicationReference)}?fileName=${urlEncode(fileName)}"
      val response = if (failures.isEmpty) http.postEmpty(url) else http.postForm(url, failures.map("failures[]" -> _))
      requireRedirect(response, s"Submit entity failures for $applicationReference")
    }

    private def submitIndividualFailures(personReference: String, failures: Seq[String], fileName: String): Unit = {
      val url = s"$frontend/agent-registration/test-only/risking/select-individual-failures/${urlEncode(personReference)}?fileName=${urlEncode(fileName)}"
      val response = if (failures.isEmpty) http.postEmpty(url) else http.postForm(url, failures.map("failures[]" -> _))
      requireRedirect(response, s"Submit individual failures for $personReference")
    }

    def waitForRiskingInputs(seeds: Seq[ApplicationSeed]): Unit = seeds.foreach { seed =>
      waitFor(s"application-for-risking ${seed.applicationReference}") { applicationReferenceExistsForRisking(seed.applicationReference) }
      seed.individuals.foreach { individual =>
        val ref = field(individual, "personReference")
        waitFor(s"individual-for-risking $ref") { personReferenceExistsForRisking(ref) }
      }
    }

    def createApplicationSeed(
                               scenario: ScenarioDefinition,
                               pool: String,
                               recordIndex: Int
                             ): ApplicationSeed = {

      val appId =
        fastForwardApplication(
          scenario.completedSectionSlug
        )

      recordCreatedApplication(appId)

      val app =
        findApplication(appId)

      val linkId =
        field(app, "linkId")

      val applicationReference =
        field(app, "applicationReference")

      if (linkId.isEmpty || applicationReference.isEmpty) {
        throw new RuntimeException(
          s"Application $appId missing linkId or applicationReference"
        )
      }

      val individuals =
        findIndividuals(appId)
          .take(scenario.linkedIndividuals)

      if (individuals.size < scenario.linkedIndividuals) {
        throw new RuntimeException(
          s"Expected at least ${scenario.linkedIndividuals} individuals for ${scenario.scenarioId}, found ${individuals.size}"
        )
      }

      submitScaleApplicationIfNeeded(
        scenario,
        appId,
        linkId,
        individuals
      )

      ApplicationSeed(
        scenario,
        pool,
        recordIndex,
        appId,
        applicationReference,
        linkId,
        s"applicant_$appId",
        s"MMTAR_$appId",
        individuals
      )
    }

    def uploadOutcomes(seed: ApplicationSeed): Unit = {

      submitEntityFailures(
        seed.applicationReference,
        seed.scenario.entityFailures,
        nextFilename(
          s"${seed.pool}-${seed.scenario.scenarioId}-entity"
        )
      )

      seed.individuals.zipWithIndex.foreach {
        case (individual, index) =>

          val ref =
            field(individual, "personReference")

          if (ref.isEmpty) {
            throw new RuntimeException(
              s"Missing personReference for application ${seed.appId}"
            )
          }

          val failures =
            seed.scenario.individualFailuresByIndex
              .getOrElse(index, Seq.empty)

          submitIndividualFailures(
            ref,
            failures,
            nextFilename(
              s"${seed.pool}-${seed.scenario.scenarioId}-individual-${index + 1}"
            )
          )
      }
    }

    private def submitScaleApplicationIfNeeded(scenario: ScenarioDefinition, appId: String, linkId: String, individuals: Seq[Obj]): Unit = {
      if (!scenario.requiresInitialSubmission) return
      println(s"      completing initial six-person submission for ${scenario.scenarioId} ...")
      individuals.foreach(completeInitialIndividualSubmission(appId, linkId, _))
      val declarationUrl = s"$frontend/agent-registration/apply/agent-declaration/confirm-declaration"
      val session = new SeederHttpClient(30)
      var declarationPage = session.followGet(applicantLoginUrl(appId, declarationUrl))
      if (!declarationPage.body.contains("Declaration")) {
        val taskListUrl = s"$frontend/agent-registration/apply/task-list"
        requireSuccess(session.followGet(applicantLoginUrl(appId, taskListUrl)), "Load applicant task list")
        declarationPage = requireSuccess(session.followGet(declarationUrl), "Load initial applicant declaration")
      }
      ensureContains(declarationPage, "Declaration", "initial applicant declaration")
      val action = extractFormAction(declarationPage.body, declarationPage.uri.toString)
      val csrf = extractCsrf(declarationPage.body)
      val post = requireRedirect(session.postForm(action, Seq("csrfToken" -> csrf, "submit" -> "AcceptAndSend")), "Submit initial applicant declaration")
      requireSuccess(session.followGet(resolveLocation(post)), "Load application status")
    }

    private def completeInitialIndividualSubmission(appId: String, linkId: String, individual: Obj): Unit = {
      val individualId = field(individual, "_id")
      val individualName = field(individual, "individualName")
      if (individualId.isEmpty || individualName.isEmpty) throw new RuntimeException(s"Missing individual identifiers for app $appId")
      val matchUrl = s"$frontend/agent-registration/provide-details/match-application/$linkId"
      val session = new SeederHttpClient(30)
      val matchPage = requireSuccess(session.followGet(individualLoginUrl(appId, individualId, individualName, matchUrl)), "Load initial match page")
      ensureContains(matchPage, "confirmMatchToIndividualProvidedDetails", "initial match page")
      val matchPost = requireRedirect(session.postForm(matchPage.uri.toString, Seq(
        "csrfToken" -> extractCsrf(matchPage.body),
        "confirmMatchToIndividualProvidedDetails" -> "Yes",
        "submit" -> "SaveAndContinue"
      )), "Confirm initial individual match")

      val phoneRedirect = requireRedirect(session.get(resolveLocation(matchPost)), "Load telephone redirect")
      val phonePage = requireSuccess(session.followGet(resolveLocation(phoneRedirect)), "Load telephone page")
      val phoneAction = extractFormAction(phonePage.body, phonePage.uri.toString)
      val phonePost = requireRedirect(session.postForm(phoneAction, Seq(
        "csrfToken" -> extractCsrf(phonePage.body), "individualTelephoneNumber" -> "07777777777", "submit" -> "SaveAndContinue"
      )), "Submit telephone")

      val emailRedirect = requireRedirect(session.get(resolveLocation(phonePost)), "Load email redirect")
      val emailPage = requireSuccess(session.followGet(resolveLocation(emailRedirect)), "Load email page")
      val emailAction = extractFormAction(emailPage.body, emailPage.uri.toString)
      val emailPost = requireRedirect(session.postForm(emailAction, Seq(
        "csrfToken" -> extractCsrf(emailPage.body), "individualEmailAddress" -> s"perf-${individualId.take(8)}@example.com", "submit" -> "SaveAndContinue"
      )), "Submit email")

      val verifyRedirect = requireRedirect(session.get(resolveLocation(emailPost)), "Verify email redirect")
      val ucrRedirect = requireRedirect(session.get(resolveLocation(verifyRedirect)), "Load UCR redirect")
      val cyaAfterUcrRedirect = requireRedirect(session.get(resolveLocation(ucrRedirect)), "Load CYA after UCR redirect")
      val approveRedirect = requireRedirect(session.get(resolveLocation(cyaAfterUcrRedirect)), "Load approve redirect")
      val approvePage = requireSuccess(session.followGet(resolveLocation(approveRedirect)), "Load approve page")
      val approveAction = extractFormAction(approvePage.body, approvePage.uri.toString)
      val approvePost = requireRedirect(session.postForm(approveAction, Seq("csrfToken" -> extractCsrf(approvePage.body), "submit" -> "SaveAndContinue")), "Submit approve page")

      val standardsPage = requireSuccess(session.followGet(resolveLocation(approvePost)), "Load standards page")
      val standardsAction = extractFormAction(standardsPage.body, standardsPage.uri.toString)
      val standardsPost = requireRedirect(session.postForm(standardsAction, Seq("csrfToken" -> extractCsrf(standardsPage.body), "submit" -> "SaveAndContinue")), "Submit standards page")

      val finalCya = requireSuccess(session.followGet(resolveLocation(standardsPost)), "Load final CYA")
      val finalAction = extractFormAction(finalCya.body, finalCya.uri.toString)
      val finalPost = requireRedirect(session.postForm(finalAction, Seq("csrfToken" -> extractCsrf(finalCya.body), "submit" -> "SaveAndContinue")), "Submit final CYA")
      val confirmation = requireSuccess(session.followGet(resolveLocation(finalPost)), "Load individual confirmation")
      ensureContains(confirmation, IndividualConfirmationMarker, "initial individual confirmation")
    }

    def completeApplicantResubmission(row: Map[String, String]): Unit = {
      val session = new SeederHttpClient(30)
      val taskListPage = followFrontendRedirects(session, row("applicant_login_url"), "applicant login")
      ensureContains(taskListPage, ApplicantTaskListMarker, "applicant fixable task list")
      if (row.getOrElse("entity_failure_url", "").nonEmpty) {
        val failurePage = requireSuccess(session.followGet(row("entity_failure_url")), "Load applicant entity failure")
        val action = extractFormAction(failurePage.body, failurePage.uri.toString)
        requireRedirect(session.postForm(action, Seq("csrfToken" -> extractCsrf(failurePage.body), "isFixed" -> "Yes")), "Confirm entity failure fixed")
      }
      val declaration = requireSuccess(session.followGet(row("declaration_url")), "Load applicant declaration")
      ensureContains(declaration, "Declaration", "applicant declaration page")
      val action = extractFormAction(declaration.body, declaration.uri.toString)
      val post = requireRedirect(session.postForm(action, Seq("csrfToken" -> extractCsrf(declaration.body), "submit" -> "AcceptAndSend")), "Submit applicant declaration")
      val status = requireSuccess(session.followGet(resolveLocation(post)), "Load resubmitted status")
      ensureContains(status, ApplicantResubmittedMarker, "already resubmitted status page")
    }

    def completeIndividualFixableJourney(row: Map[String, String]): Unit = {
      val session = new SeederHttpClient(30)
      requireSuccess(session.followGet(row("individual_login_url")), "Individual login")
      if (row.getOrElse("failure_details_url", "").nonEmpty) {
        val failurePage = requireSuccess(session.followGet(row("failure_details_url")), "Load individual failure details")
        val action = extractFormAction(failurePage.body, failurePage.uri.toString)
        requireRedirect(session.postForm(action, Seq("csrfToken" -> extractCsrf(failurePage.body), "isFixed" -> "Yes")), "Confirm individual failure fixed")
      }
      if (row.getOrElse("identity_url", "").nonEmpty) {
        requireSuccess(session.followGet(row("identity_url")), "Load individual identity")
        Seq(
          row("dob_url") -> Seq("dateOfBirth.day" -> "24", "dateOfBirth.month" -> "04", "dateOfBirth.year" -> "2006"),
          row("nino_url") -> Seq("individualNino.hasNino" -> "Yes", "individualNino.nino" -> "AA123456A"),
          row("sautr_url") -> Seq("individualSaUtr.hasSaUtr" -> "Yes", "individualSaUtr.saUtr" -> "1234567890")
        ).foreach { case (url, payload) =>
          val page = requireSuccess(session.followGet(url), s"Load $url")
          val action = extractFormAction(page.body, page.uri.toString)
          requireRedirect(session.postForm(action, Seq("csrfToken" -> extractCsrf(page.body), "submit" -> "SaveAndContinue") ++ payload), s"Submit $url")
        }
        val cya = requireSuccess(session.followGet(row("check_your_answers_url")), "Load individual CYA")
        val cyaAction = extractFormAction(cya.body, cya.uri.toString)
        requireRedirect(session.postForm(cyaAction, Seq("csrfToken" -> extractCsrf(cya.body), "submit" -> "SaveAndContinue")), "Submit individual CYA")
      }
      val declaration = requireSuccess(session.followGet(row("declaration_url")), "Load individual declaration")
      ensureContains(declaration, "You are about to submit your responses", "individual declaration page")
      val action = extractFormAction(declaration.body, declaration.uri.toString)
      val post = requireRedirect(session.postForm(action, Seq("csrfToken" -> extractCsrf(declaration.body), "submit" -> "AcceptAndSend")), "Submit individual declaration")
      val confirmation = requireSuccess(session.followGet(resolveLocation(post)), "Load individual confirmation")
      ensureContains(confirmation, IndividualConfirmationMarker, "individual confirmation page")
    }

    private def followFrontendRedirects(session: SeederHttpClient, url: String, description: String, maxRedirects: Int = 20): SeederResponse = {
      var currentUrl = url
      var redirects = 0
      while (redirects < maxRedirects) {
        val response = session.get(currentUrl)
        if (!isRedirect(response.status)) return requireSuccess(response, s"Load $description")
        currentUrl = resolveLocation(response)
        if (!currentUrl.startsWith(frontend)) throw new RuntimeException(s"Unexpected redirect for $description to $currentUrl")
        redirects += 1
      }
      throw new RuntimeException(s"Too many redirects while loading $description from $url")
    }
  }
}

