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
import io.gatling.http.request.builder.HttpRequestBuilder
import uk.gov.hmrc.performance.conf.ServicesConfiguration

object RiskingResultsFileRequests extends ServicesConfiguration {

  private val riskingBaseUrl: String = baseUrlFor("agent-registration-risking")

  // Data setup combines everything into ONE unprocessed file. If setup failed part way there will be several
  // unprocessed files (or none), so the check fails and the request is reported as KO.
  val getUnprocessedAvailableFilesExpectingOne: HttpRequestBuilder =
    http("Get unprocessed risking results files (expect exactly 1 combined file)")
      .get(s"$riskingBaseUrl/agent-registration-risking/test-only/unprocessed-available-files")
      .check(status.is(200))
      .check(jsonPath("$[*].filename").count.is(1))

  val getUnprocessedAvailableFilesExpectingNone: HttpRequestBuilder =
    http("Get unprocessed risking results files (expect none after processing)")
      .get(s"$riskingBaseUrl/agent-registration-risking/test-only/unprocessed-available-files")
      .check(status.is(200))
      .check(jsonPath("$[*].filename").count.is(0))

  val runResultsFileProcessing: HttpRequestBuilder =
    http("Run risking results file processing")
      .get(s"$riskingBaseUrl/agent-registration-risking/test-only/run-results-file-processing")
      .check(status.is(200))
}

