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
import ujson.{Arr, Obj}
import SeederSupport._

object PerformanceTestDataCleanup {

  final case class Config(
                           backendUrl: String = "http://localhost:22202",
                           provideDetailsManifest: String =
                           "tmp/performance-test-cleanup/provide-details.json",
                           riskOutcomesManifest: String =
                           "tmp/performance-test-cleanup/risk-outcomes.json",
                           riskingResultsFileManifest: String =
                           "tmp/performance-test-cleanup/risking-results-file.json"
                         )

  def main(args: Array[String]): Unit = {
    val parsed = parseArgs(args)

    val config = Config(
      backendUrl =
        option(
          parsed,
          "backend-url",
          "http://localhost:22202"
        ).stripSuffix("/"),
      provideDetailsManifest =
        option(
          parsed,
          "provide-details-manifest",
          "tmp/performance-test-cleanup/provide-details.json"
        ),
      riskOutcomesManifest =
        option(
          parsed,
          "risk-outcomes-manifest",
          "tmp/performance-test-cleanup/risk-outcomes.json"
        ),
      riskingResultsFileManifest =
        option(
          parsed,
          "risking-results-file-manifest",
          "tmp/performance-test-cleanup/risking-results-file.json"
        )
    )

    // Limits cleanup to the risking results file manifest so other simulations' data is left alone.
    val onlyRiskingResultsFile = parsed.get("only-risking-results-file").contains("true")

    val provideDetailsIds =
      if (onlyRiskingResultsFile) Seq.empty[String]
      else readCleanupManifest(config.provideDetailsManifest)

    val riskOutcomeIds =
      if (onlyRiskingResultsFile) Seq.empty[String]
      else readCleanupManifest(config.riskOutcomesManifest)

    val riskingResultsFileIds =
      readCleanupManifest(
        config.riskingResultsFileManifest
      )

    val applicationIds =
      (
        provideDetailsIds ++
          riskOutcomeIds ++
          riskingResultsFileIds
        ).distinct

    println("\nPerformance test cleanup")
    println(s"  Provide-details applications : ${provideDetailsIds.size}")
    println(s"  Risk-outcome applications    : ${riskOutcomeIds.size}")
    println(s"  Results-file applications    : ${riskingResultsFileIds.size}")
    println(s"  Total unique applications    : ${applicationIds.size}")

    if (applicationIds.isEmpty) {
      println("\nNo seeded applications found. Nothing to clean up.\n")
      return
    }

    val http =
      new SeederHttpClient(
        30,
        persistCookies = false
      )

    val payload =
      Obj(
        "agentApplicationIds" ->
          Arr.from(
            applicationIds.map(
              ujson.Str(_)
            )
          )
      )

    val response =
      requireSuccess(
        http.postJson(
          s"${config.backendUrl}/agent-registration/test-only/applications/cleanup",
          payload
        ),
        "Cleanup seeded performance-test applications"
      )

    if (response.status != 204) {
      throw new RuntimeException(
        s"Cleanup expected HTTP 204 but got ${response.status}"
      )
    }

    if (!onlyRiskingResultsFile) {
      deleteManifest(config.provideDetailsManifest)
      deleteManifest(config.riskOutcomesManifest)
    }

    deleteManifest(
      config.riskingResultsFileManifest
    )

    println(
      s"\n✓ Deleted ${applicationIds.size} seeded applications and their individuals"
    )

    println("✓ Cleanup manifests removed\n")
  }

  private def deleteManifest(
                              path: String
                            ): Unit = {
    Files.deleteIfExists(
      Paths.get(path)
    )

    ()
  }
}