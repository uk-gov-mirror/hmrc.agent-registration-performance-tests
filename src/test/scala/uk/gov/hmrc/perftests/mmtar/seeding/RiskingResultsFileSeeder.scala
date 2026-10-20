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

import SeederSupport._
import RiskOutcomeSeeder.{ApplicationSeed, Config, ScenarioDefinition}

/**
 * Data preparation for AgentRegistrationRiskingResultsFileProcessingSimulation.
 *
 * Creates N real applications (with linked individuals) using the same flows as the risk outcome seeder,
 * runs risking, uploads a risking outcome file per entity/individual with a mix of outcomes, and then
 * combines all unprocessed results files into one large file. It deliberately does NOT run results file
 * processing - that is the step measured by the simulation.
 */
object RiskingResultsFileSeeder {

  // Scenarios that need no post-seed journeys or initial submission, giving a mix of
  // FailedFixable (entity and individual), FailedNonFixable and Approved outcomes.
  private val includedScenarioIds: Set[String] = Set(
    "APP-ST-FIX-RESUB",
    "APP-LTD-FIX-RESUB-2",
    "APP-LLP-FIX-RESUB-2",
    "APP-GP-FIX-RESUB-2",
    "APP-SP-FIX-RESUB-2",
    "APP-LP-FIX-RESUB-2",
    "APP-SLP-FIX-RESUB-2",
    "APP-AMLS-FIX-RESUB",
    "IND-FIX-DETAILS",
    "IND-FIX-CONFIRM-ONLY",
    "IND-NONFIXABLE-CONTROL",
    "IND-APPROVED-CONTROL"
  )

  private val approvedScenarioId = "IND-APPROVED-CONTROL"

  /** Approvals outnumber all other outcomes combined by this ratio (approved : non-approved = N : 1). */
  private val approvedToOtherRatio: Int = sys.env.get("RISKING_RESULTS_APPROVED_RATIO").flatMap(_.toIntOption).getOrElse(5)

  private val scenarioMix: Seq[ScenarioDefinition] =
    RiskOutcomeSeeder.scenarios.filter(s => includedScenarioIds.contains(s.scenarioId))

  private val otherWeight: Int =
    scenarioMix.filterNot(_.scenarioId == approvedScenarioId).map(s => math.max(1, s.weight)).sum

  private def effectiveWeight(s: ScenarioDefinition): Int =
    if (s.scenarioId == approvedScenarioId) math.max(1, approvedToOtherRatio * otherWeight)
    else math.max(1, s.weight)

  /** Deterministic weighted round-robin so every scenario is represented, proportionally to its weight. */
  def scenarioForIndex(index: Int): ScenarioDefinition = {
    val weighted = scenarioMix.flatMap(s => Seq.fill(effectiveWeight(s))(s))
    // Interleave by stepping through with a stride coprime to the list size to avoid long runs of one scenario.
    val size = weighted.size
    val stride = Iterator.from(7).find(n => gcd(n, size) == 1).getOrElse(1)
    weighted((index * stride) % size)
  }

  /** One entity record plus one record per linked individual. */
  private def recordsFor(s: ScenarioDefinition): Int = 1 + math.max(1, s.linkedIndividuals)

  /** Plans applications until the combined file holds as close to (but not over) the target number of records. */
  private def planScenarios(targetRecords: Int): Seq[ScenarioDefinition] = {
    val result = scala.collection.mutable.ArrayBuffer.empty[ScenarioDefinition]
    var total = 0
    var index = 0
    var done = false
    while (!done) {
      val s = scenarioForIndex(index)
      if (total + recordsFor(s) <= targetRecords) {
        result += s
        total += recordsFor(s)
        index += 1
      } else done = true
    }
    result.toSeq
  }

  private def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /** The combine only deletes the source files after the combined file is uploaded, so retrying a failed attempt is safe. */
  private def combineWithRetry(seeder: RiskOutcomeSeeder.Seeder, attempts: Int = 3): Unit = {
    var attempt = 1
    var done = false
    while (!done) {
      try {
        seeder.combineAllUnprocessedResultsFiles()
        done = true
      } catch {
        case e: Exception if attempt < attempts =>
          println(s"      combine attempt $attempt failed (${e.getMessage.take(200)}); retrying...")
          Thread.sleep(2000L * attempt)
          attempt += 1
        case e: Exception =>
          throw new RuntimeException(
            "Combining risking results files failed repeatedly. A common cause is the combined file exceeding the risking " +
              "service's request body limit (HTTP 413 'Request Entity Too Large' on the test-only upload; Play defaults to 100KB). " +
              "Start agent-registration-risking with -Dplay.http.parser.maxMemoryBuffer=10MB, or lower RISKING_RESULTS_RECORDS. " +
              "Check the agent-registration-frontend console for the underlying error.",
            e
          )
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val parsed = parseArgs(args)

    val planned: Seq[ScenarioDefinition] = parsed.get("applications") match {
      case Some(_) => (0 until intOption(parsed, "applications", 0)).map(scenarioForIndex)
      case None    => planScenarios(intOption(parsed, "records", 750))
    }
    val applications = planned.size
    require(applications >= 2, "need at least 2 applications so that there are 2+ files to combine")

    val config = Config(
      backendUrl = option(parsed, "backend-url", "http://localhost:22202").stripSuffix("/"),
      frontendUrl = option(parsed, "frontend-url", "http://localhost:22201").stripSuffix("/"),
      stubsUrl = option(parsed, "stubs-url", "http://localhost:9099").stripSuffix("/"),
      outputDir = "",
      pool = "results-file",
      totalPeakJps = 0,
      rampupMinutes = 0,
      steadyMinutes = 0,
      rampdownMinutes = 0,
      bufferPercent = 0,
      loadMainRecords = None,
      loadControlRecords = None,
      loadScaleRecords = None,
      smokeRecords = 0,
      cleanupManifest = option(
        parsed,
        "cleanup-manifest",
        "tmp/performance-test-cleanup/risking-results-file.json"
      ),
      dryRun = parsed.get("dry-run").contains("true")
    )

    println("\nRisking results file seeding")
    println(s"  Backend      : ${config.backendUrl}")
    println(s"  Frontend     : ${config.frontendUrl}")
    println(s"  Applications : $applications")
    println(s"  Records      : ${planned.map(recordsFor).sum}")
    println(s"  Cleanup      : ${config.cleanupManifest}")

    planned.groupBy(_.scenarioId).toSeq.sortBy(_._1).foreach { case (id, list) =>
      println(f"  $id%-28s ${list.size}")
    }

    if (config.dryRun) {
      println("\nDry run complete. No API calls made.")
      return
    }

    val seeder = new RiskOutcomeSeeder.Seeder(config)

    requireSuccess(
      seeder.http.get(s"${config.backendUrl}/agent-registration/test-only/recent-applications"),
      "Backend readiness check"
    )

    println("\n  Creating applications...")
    val seeds: Seq[ApplicationSeed] = planned.zipWithIndex.map { case (scenario, i) =>
      println(s"  [results-file] ${scenario.scenarioId} ${i + 1}/$applications")
      seeder.createApplicationSeed(scenario, config.pool, i + 1)
    }

    println("\n  Running risking for all newly created records...")
    seeder.runRisking()
    seeder.waitForRiskingInputs(seeds)

    // Combining everything in one go makes the frontend fetch every file concurrently, which times out
    // against the risking service. Instead upload a small batch, then combine, so each combine only has to
    // merge the previous combined file plus a few dozen new files.
    val batchSize = intOption(parsed, "combine-batch-size", 10)
    require(batchSize >= 1, "combine-batch-size must be at least 1")

    val batches = seeds.grouped(batchSize).toSeq
    println(s"\n  Uploading risking outcomes and combining after every $batchSize applications (${batches.size} batches)...")

    batches.zipWithIndex.foreach { case (batch, batchIndex) =>
      batch.foreach(seeder.uploadOutcomes)
      println(s"    batch ${batchIndex + 1}/${batches.size}: uploaded outcomes for ${batch.size} applications, combining...")
      combineWithRetry(seeder)
    }

    val individualCount = seeds.map(_.individuals.size).sum
    println(
      s"\nDone. ${seeds.size} applications seeded; combined file holds ${seeds.size} entity and $individualCount individual outcomes " +
        s"(${seeds.size + individualCount} records). Results file processing has NOT been run."
    )
  }
}
