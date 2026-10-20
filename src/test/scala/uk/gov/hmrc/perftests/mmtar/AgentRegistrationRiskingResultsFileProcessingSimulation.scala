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

package uk.gov.hmrc.perftests.mmtar

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import uk.gov.hmrc.perftests.mmtar.seeding.PerformanceTestDataCleanup

class AgentRegistrationRiskingResultsFileProcessingSimulation extends Simulation {

  private val httpProtocol =
    http.disableFollowRedirect

  // Data prep (prepareRiskingResultsFileData) has already created the applications and combined
  // all their results into one large file. This scenario only measures processing that file.
  // The two list requests carry real checks so an incomplete data setup fails the simulation.
  private val largeResultsFileProcessingScenario =
    scenario("Process a large combined risking results file")
      .exec(RiskingResultsFileRequests.getUnprocessedAvailableFilesExpectingOne)
      .exec(RiskingResultsFileRequests.runResultsFileProcessing)
      .exec(RiskingResultsFileRequests.getUnprocessedAvailableFilesExpectingNone)

  // Remove the applications created by data prep once the run finishes (pass or fail).
  // Set RISKING_RESULTS_SKIP_CLEANUP=true to keep them, e.g. for investigation.
  after {
    val skip = sys.env.get("RISKING_RESULTS_SKIP_CLEANUP").exists(_.equalsIgnoreCase("true"))
    if (skip) println("Skipping cleanup of risking results file applications (RISKING_RESULTS_SKIP_CLEANUP=true)")
    else {
      val runLocal = sys.props.get("runLocal").forall(_ == "true")
      val backendUrl =
        if (runLocal) "http://localhost:22202"
        else sys.env.getOrElse("BACKEND_URL", "https://agent-registration.protected.mdtp")
      try
        PerformanceTestDataCleanup.main(
          Array("--backend-url", backendUrl, "--only-risking-results-file", "true")
        )
      catch {
        case e: Exception => println(s"Cleanup of risking results file applications failed: ${e.getMessage}")
      }
    }
  }

  setUp(
    largeResultsFileProcessingScenario.inject(
      atOnceUsers(1)
    )
  )
    .protocols(httpProtocol)
    .assertions(
      global.failedRequests.percent.lte(1)
    )
}
