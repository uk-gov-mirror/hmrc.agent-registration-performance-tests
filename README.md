# agent-registration-performance-tests

Performance test suite for Agent Registration, using HMRC's `performance-test-runner`.

The suite currently covers:

* a full sole trader registration journey, run through `AgentRegistrationSimulation`
* a targeted provide-details contention check, run through `AgentRegistrationProvideDetailsContentionSimulation`
* deterministic applicant and individual risk-outcome journeys, run through `AgentRegistrationRiskOutcomeSimulation`
* a one-shot large risking-results-file processing check, run through `AgentRegistrationRiskingResultsFileProcessingSimulation`

The sole trader journey is the standard rate-based performance journey.

The provide-details contention simulation is separate from the rate-based `journeys.conf` setup. It starts six users at once against the same pre-seeded application, representing the six listed individuals providing details concurrently.

The risk-outcome coverage is also separate from `journeys.conf`.

It uses a dedicated Gatling simulation because it needs:

* deterministic pre-seeded submitted and risked applications
* separate feeders per scenario
* a weighted multi-scenario mix
* a clear split between setup time and timed load time

## Pre-requisites

### Local services

Start MongoDB using the MDTP Handbook instructions.

Start the stubbed Agent Registration services:

```bash
sm2 --start AGENT_REGISTRATION_STUBBED_GRS
```

Stop the backend and frontend if they are already running under `sm2`:

```bash
sm2 --stop AGENT_REGISTRATION
sm2 --stop AGENT_REGISTRATION_FRONTEND
```

Start `agent-registration` manually with test-only routes enabled:

```bash
cd ../agent-registration
sbt -Dapplication.router=testOnlyDoNotUseInAppConf.Routes run
```

Start `agent-registration-frontend` manually with test-only routes enabled and email verification disabled:

```bash
cd ../agent-registration-frontend
sbt -DignoreEmailVerification=true -Dapplication.router=testOnlyDoNotUseInAppConf.Routes run
```

The local default service URLs are:

```text
agent-registration:          http://localhost:22202
agent-registration-frontend: http://localhost:22201
agents-external-stubs:       http://localhost:9099
```

### Logging

The default HTTP request log level is controlled by:

```text
src/test/resources/logback.xml
```

Use `-DdebugRequests=true` when running locally if request-level debug output is needed.

Use `-DdebugRequests=false` for normal local and Jenkins runs.

## Framework and repo structure

This repository is a Scala/SBT Gatling suite using HMRC's `performance-test-runner`.

Performance-test data seeding is also implemented in Scala. The SBT preparation tasks invoke the seeders directly, so Python and the third-party `requests` package are not required.

Relevant files for the risk-outcome setup are:

```text
build.sbt
src/test/scala/uk/gov/hmrc/perftests/mmtar/AgentRegistrationRiskOutcomeSimulation.scala
src/test/scala/uk/gov/hmrc/perftests/mmtar/RiskOutcomeRequests.scala
scripts/prepare_risk_outcome_data.sh
src/test/scala/uk/gov/hmrc/perftests/mmtar/seeding/RiskOutcomeSeeder.scala
src/test/resources/data/risk-outcomes/
```

## Warning

Do not run a full staging performance test from your local machine.

Full staging performance tests should be executed from Performance Jenkins:

```text
https://performance.tools.staging.tax.service.gov.uk
```

## Simulations

### AgentRegistrationSimulation

`AgentRegistrationSimulation` runs the standard rate-based performance journey configured in `journeys.conf`.

It currently runs the full sole trader registration journey.

The sole trader journey submits a complete Agent Registration application from start to finish.

Each sole trader virtual user creates a new independent application.

### AgentRegistrationProvideDetailsContentionSimulation

`AgentRegistrationProvideDetailsContentionSimulation` runs a targeted contention check.

It is not configured as a normal `journeys.conf` journey because `journeys.conf` load values represent journeys per second.

The required behaviour for this test is not sustained throughput. It is a specific contention event where six listed individuals access the same pre-seeded application at the same time.

The simulation uses:

```scala
atOnceUsers(6)
```

Each seeded application has six listed individuals.

The generated feeder is grouped so that the first six rows belong to the same application, the next six rows belong to the next application, and so on.

Example feeder grouping:

```text
app-0001-individual-0001
app-0001-individual-0002
app-0001-individual-0003
app-0001-individual-0004
app-0001-individual-0005
app-0001-individual-0006

app-0002-individual-0001
app-0002-individual-0002
...
```

Each row contains an individual sign-in slot that continues directly to:

```text
/agent-registration/provide-details/match-application/:linkId
```

The generated feeder is:

```text
src/test/resources/data/provide-details-concurrency.csv
```

### AgentRegistrationRiskOutcomeSimulation

`AgentRegistrationRiskOutcomeSimulation` runs deterministic applicant and individual risk-outcome journeys against already-submitted and already-risked applications.

Timed load does **not** create prerequisite applications through the UI.

Setup is performed first by:

```text
scripts/prepare_risk_outcome_data.sh
src/test/scala/uk/gov/hmrc/perftests/mmtar/seeding/RiskOutcomeSeeder.scala
```

The default execution profile is:

```text
1 minute ramp up
8 minutes steady load
1 minute ramp down
```

The default feeder pool is:

```text
load
```

Optional smoke feeders can also be generated.

### AgentRegistrationRiskingResultsFileProcessingSimulation

`AgentRegistrationRiskingResultsFileProcessingSimulation` measures how long the risking service takes to process one large results file containing outcomes for real applications held in Mongo.

It is split into data setup and the timed test.

**Data setup** (`prepareRiskingResultsFileData`, also run as part of `preparePerformanceTestData`)

1. Creates 750 applications (default) using the same fast-forward flows as the risk-outcome seeder, with a weighted mix of outcomes (fixable entity failures, fixable individual failures, non-fixable failures and approvals) across sole trader, limited company, LLP and the partnership types.
2. Uploads one risking results file per entity and individual, and calls `POST /agent-registration/test-only/risking/risking-results-files/combine-all-unprocessed` on the frontend after every batch of applications (10 by default, `RISKING_RESULTS_COMBINE_BATCH_SIZE`). Each call merges the previous combined file plus the new batch, so the end result is one big unprocessed file. Combining everything in a single call makes the frontend fetch ~1,700 files concurrently and times out against the risking service.
3. Stops. Results file processing is deliberately not run here.

**Test**

1. Confirms there is an unprocessed results file.
2. Runs results file processing (the step being measured).
3. Confirms no unprocessed files remain.

Each application has one entity record plus its linked individuals, so the seeder plans applications until the combined file holds up to `RISKING_RESULTS_RECORDS` records.

Settings:

```bash
RISKING_RESULTS_RECORDS=750        # or -DriskingResults.records=750
RISKING_RESULTS_SKIP_SEED=true     # skip data setup
RISKING_RESULTS_DRY_RUN=true       # print the plan only, no API calls
```

Seeded applications are recorded in `tmp/performance-test-cleanup/risking-results-file.json` and removed by `cleanupPerformanceTestData`.

Prepare the data only:

```bash
sbt -DrunLocal=true prepareRiskingResultsFileData
```

Run the timed test only (after data setup):

```bash
RISKING_RESULTS_SKIP_SEED=true sbt -DrunLocal=true \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationRiskingResultsFileProcessingSimulation"
```

## Risk-outcome scenario matrix

The risk-outcome simulation currently implements these separately named Gatling scenarios.

### Applicant scenarios

```text
APP-ST-FIX-RESUB
APP-LTD-FIX-RESUB-2
APP-LLP-FIX-RESUB-2
APP-GP-FIX-RESUB-2
APP-SP-FIX-RESUB-2
APP-LP-FIX-RESUB-2
APP-SLP-FIX-RESUB-2
APP-AMLS-FIX-RESUB
APP-RESUBMITTED-STATUS
APP-LTD-FIX-RESUB-6
APP-LLP-FIX-RESUB-6
APP-GP-FIX-RESUB-6
```

### Individual scenarios

```text
IND-FIX-DETAILS
IND-FIX-CONFIRM-ONLY
IND-FIX-ALREADY-CONFIRMED
IND-NONFIXABLE-CONTROL
IND-APPROVED-CONTROL
```

## Deterministic risk-outcome seeding

The risk-outcome data preparation flow does the following before timed load starts:

1. creates submitted applications in the required business type
2. creates or completes the required linked individuals
3. runs risking
4. uploads deterministic entity outcomes
5. uploads deterministic individual outcomes
6. runs results-file processing
7. optionally pre-completes control-state rows such as:
   * already resubmitted applicant status
   * already confirmed individual fixable journey
8. writes feeder CSV files and a JSON manifest

The main risk-outcome setup intentionally avoids the random quick `fixable` and `non-fixable` endpoints.

Instead it posts stable failure selections to the test-only select-failures routes.

### Seeded feeder output

Generated feeder files are written under:

```text
src/test/resources/data/risk-outcomes/<pool>/
```

Per-scenario files are created, for example:

```text
src/test/resources/data/risk-outcomes/load/APP-ST-FIX-RESUB.csv
src/test/resources/data/risk-outcomes/load/IND-FIX-DETAILS.csv
```

Aggregate inspection files are also created:

```text
src/test/resources/data/risk-outcomes/load/applicant-scenarios.csv
src/test/resources/data/risk-outcomes/load/individual-scenarios.csv
src/test/resources/data/risk-outcomes/load/control-scenarios.csv
src/test/resources/data/risk-outcomes/load/two-person-variants.csv
src/test/resources/data/risk-outcomes/load/six-person-variants.csv
src/test/resources/data/risk-outcomes/load/manifest.json
src/test/resources/data/risk-outcomes/manifest.json
```

### Minimum feeder fields

Applicant scenario rows include at least:

```text
scenario_id
business_type
application_reference
applicant_login_url
applicant_start_url
expected_outcome
```

Individual scenario rows include at least:

```text
scenario_id
business_type
application_reference
person_reference
link_id
individual_name
individual_login_url
individual_start_url
expected_outcome
```

Additional route-specific columns are also written so each scenario can jump directly to the deterministic fix pages it needs.

## Risk-outcome setup modes

### Setup only

Run the deterministic risk-outcome seed without starting Gatling:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
scripts/prepare_risk_outcome_data.sh
```

Or via SBT:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

sbt -DrunLocal=true prepareRiskOutcomeData
```

### Execute only

If feeder data has already been prepared, run only the timed risk-outcome simulation:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

sbt -DrunLocal=true \
    -DriskOutcome.pool=load \
    -DriskOutcome.totalPeakJps=0.1 \
    -DriskOutcome.rampUpMinutes=1 \
    -DriskOutcome.steadyMinutes=8 \
    -DriskOutcome.rampDownMinutes=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationRiskOutcomeSimulation"
```

### Full local run

Prepare the risk-outcome feeders first, then run the simulation:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

sbt -DrunLocal=true prepareRiskOutcomeData

sbt -DrunLocal=true \
    -DriskOutcome.pool=load \
    -DriskOutcome.totalPeakJps=0.1 \
    -DriskOutcome.rampUpMinutes=1 \
    -DriskOutcome.steadyMinutes=8 \
    -DriskOutcome.rampDownMinutes=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationRiskOutcomeSimulation"
```

### Full Jenkins-style run

`gatling:test` now prepares both deterministic data sets before running all simulations:

```bash
sbt -DrunLocal=false gatling:test
```

That includes:

```text
prepareProvideDetailsConcurrencyData
prepareRiskOutcomeData
AgentRegistrationSimulation
AgentRegistrationProvideDetailsContentionSimulation
AgentRegistrationRiskOutcomeSimulation
```

## Risk-outcome setup tuning

Useful setup environment variables:

```text
RISK_OUTCOME_POOLS
RISK_OUTCOME_OUTPUT_DIR
RISK_OUTCOME_TOTAL_PEAK_JPS
RISK_OUTCOME_RAMPUP_MINUTES
RISK_OUTCOME_STEADY_MINUTES
RISK_OUTCOME_RAMPDOWN_MINUTES
RISK_OUTCOME_BUFFER_PERCENT
RISK_OUTCOME_LOAD_MAIN_RECORDS
RISK_OUTCOME_LOAD_CONTROL_RECORDS
RISK_OUTCOME_LOAD_SCALE_RECORDS
RISK_OUTCOME_SMOKE_RECORDS
```

Examples:

```bash
RISK_OUTCOME_POOLS=smoke,load \
RISK_OUTCOME_SMOKE_RECORDS=1 \
RISK_OUTCOME_LOAD_MAIN_RECORDS=10 \
RISK_OUTCOME_LOAD_CONTROL_RECORDS=3 \
RISK_OUTCOME_LOAD_SCALE_RECORDS=5 \
scripts/prepare_risk_outcome_data.sh
```

## Assumptions and pragmatic gaps

### 6-person parent applications

The frontend test-only fixtures have complete submitted declaration states for the common 2-person risk-outcome journeys.

For the 6-person scale variants, setup uses a pragmatic path:

1. fast-forward to a 6-person pre-declaration state
2. complete each linked individual's initial provide-details path
3. submit the applicant declaration
4. run risking and deterministic results processing

This keeps the timed risk-outcome phase free from prerequisite creation work.

### AMLS scenario scope

`APP-AMLS-FIX-RESUB` uses a deterministic HMRC AMLS path.

That covers:

```text
failure details
supervisory body
registration number
evidence-route redirect behaviour
check your answers
resubmission
```

It does **not** automate a non-HMRC file upload through Upscan during the timed journey.

That is an intentional pragmatic boundary for now and should be extended separately if non-HMRC upload performance coverage becomes a specific requirement.

## Journeys

### Sole trader journey

Configured in `journeys.conf` as:

```text
sole-trader-journey
```

This journey submits a complete Agent Registration application from start to finish.

### Provide-details contention

The provide-details contention flow is not configured in `journeys.conf`.

Do not add the old `provide-details-concurrency-journey` back to `journeys.conf` unless you deliberately want to run a sustained journeys-per-second workload.

The previous `load = 6` model meant six new provide-details journeys every second. That is not the intended contention check.

The intended contention check is handled by:

```text
uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation
```

## Local sole trader performance test

Run the standard sole trader simulation with the configured 10-minute profile:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

sbt -DdebugRequests=false \
    -DrunLocal=true \
    -Dperftest.loadPercentage=100 \
    -Dperftest.rampupTime=1 \
    -Dperftest.constantRateTime=8 \
    -Dperftest.rampdownTime=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationSimulation"
```

The standard 10-minute profile is:

```text
1 minute ramp up
8 minutes at constant rate
1 minute ramp down
```

## Local provide-details contention test

Seed one pre-created application with six listed individuals:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=1 \
scripts/prepare_provide_details_concurrency_data.sh
```

Check the feeder:

```bash
wc -l src/test/resources/data/provide-details-concurrency.csv
head -7 src/test/resources/data/provide-details-concurrency.csv
```

For one seeded application, the CSV should contain one header row plus six individual rows:

```text
7 src/test/resources/data/provide-details-concurrency.csv
```

Run the targeted contention simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation"
```

The expected result is:

```text
6 users started at once
150 requests total
0 failed requests
```

## Local end-to-end validation

To run all three simulations locally, run the sole trader simulation first, then the contention simulation, then the risk-outcome simulation.

Seed the provide-details data before running the contention simulation:

```bash
cd /path/to/git/folder/agent-registration-performance-tests

BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=1 \
scripts/prepare_provide_details_concurrency_data.sh
```

Run the standard sole trader simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    -Dperftest.loadPercentage=100 \
    -Dperftest.rampupTime=1 \
    -Dperftest.constantRateTime=8 \
    -Dperftest.rampdownTime=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationSimulation"
```

Then run the targeted contention simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation"
```

Prepare the deterministic risk-outcome feeders:

```bash
BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
RISK_OUTCOME_POOLS=load \
scripts/prepare_risk_outcome_data.sh
```

Then run the risk-outcome simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    -DriskOutcome.pool=load \
    -DriskOutcome.totalPeakJps=0.1 \
    -DriskOutcome.rampUpMinutes=1 \
    -DriskOutcome.steadyMinutes=8 \
    -DriskOutcome.rampDownMinutes=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationRiskOutcomeSimulation"
```

## Jenkins performance test

The Jenkins performance job runs:

```bash
sbt -DrunLocal=false gatling:test
```

Because `gatling:test` runs all Gatling `Simulation` classes, the Jenkins job is expected to run all simulations:

```text
uk.gov.hmrc.perftests.mmtar.AgentRegistrationSimulation
uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation
uk.gov.hmrc.perftests.mmtar.AgentRegistrationRiskOutcomeSimulation
```

The expected order is:

```text
1. `prepareProvideDetailsConcurrencyData` runs before Gatling
2. provide-details contention data is seeded
3. `prepareRiskOutcomeData` runs before Gatling
4. deterministic risk-outcome data is seeded
5. `AgentRegistrationSimulation` runs
6. `AgentRegistrationProvideDetailsContentionSimulation` runs
7. `AgentRegistrationRiskOutcomeSimulation` runs
8. Gatling reports are generated and published
```

The Jenkins job should not reset shared staging data by default.

Recommended Jenkins seed defaults:

```text
RESET_BEFORE_SEED=false
PROVIDE_DETAILS_APPS=5
RISK_OUTCOME_POOLS=load
```

This creates five pre-seeded applications, each with six listed individuals, giving thirty feeder rows for the targeted contention simulation.

Only six rows are required for a single contention run, but five applications gives a small safety buffer.

## Jenkins data preparation

The Jenkins data preparation is wired in `build.sbt` using:

```scala
preparePerformanceTestData := Def.sequential(
    prepareProvideDetailsConcurrencyData,
    prepareRiskOutcomeData
).value

Gatling / test := (Gatling / test).dependsOn(preparePerformanceTestData).value
```

This means both deterministic setup tasks run once before Gatling starts.

They do not reset and reseed between simulations unless you deliberately opt into reset.

The recommended `build.sbt` environment block is:

```scala
val env = Seq(
  "RESET_URL" -> sys.env.getOrElse(
    "RESET_URL",
    "https://agent-registration-frontend.public.mdtp/agent-registration/test-only/reset"
  ),
  "RESET_BEFORE_SEED" -> sys.env.getOrElse("RESET_BEFORE_SEED", "false"),
  "PROVIDE_DETAILS_APPS" -> sys.env.getOrElse("PROVIDE_DETAILS_APPS", "5"),
  "RISK_OUTCOME_POOLS" -> sys.env.getOrElse("RISK_OUTCOME_POOLS", "load"),
  "RISK_OUTCOME_TOTAL_PEAK_JPS" -> sys.env.getOrElse("RISK_OUTCOME_TOTAL_PEAK_JPS", "0.1")
)
```

Use `RESET_BEFORE_SEED=true` only if you deliberately want to clear the target environment first.

Do not use reset in shared staging unless agreed with the team.

## Manual Jenkins-style data preparation

The Jenkins-style provide-details seed can be run manually with:

```bash
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=5 \
scripts/prepare_provide_details_concurrency_data.sh
```

For local services, include the local URLs:

```bash
BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=5 \
scripts/prepare_provide_details_concurrency_data.sh
```

The Jenkins-style risk-outcome seed can be run manually with:

```bash
RESET_BEFORE_SEED=false \
RISK_OUTCOME_POOLS=load \
scripts/prepare_risk_outcome_data.sh
```

For local services, include the local URLs:

```bash
BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
RISK_OUTCOME_POOLS=load \
scripts/prepare_risk_outcome_data.sh
```

## Useful data preparation overrides

```bash
RESET_BEFORE_SEED=false
```

Skips the reset endpoint. This is the recommended default for shared staging.

```bash
RESET_BEFORE_SEED=true
```

Calls the configured reset endpoint before seeding. Use only when a clean environment is required and it is safe to delete existing data.

```bash
PROVIDE_DETAILS_APPS=1
```

Seeds one application and six individuals. This is enough for one local contention run.

```bash
PROVIDE_DETAILS_APPS=5
```

Seeds five applications and thirty individuals. This is the recommended Jenkins default.

```bash
RISK_OUTCOME_DRY_RUN=true
```

Build the deterministic risk-outcome feeder plan without making API calls.

## Things not to do

Do not re-add the old sustained provide-details journey unless intentionally testing sustained throughput:

```hocon
provide-details-concurrency-journey = {
  load = 6
  ...
}
```

That old configuration means:

```text
6 provide-details journeys per second
```

For a 1/8/1 profile, that creates thousands of individual journeys and does not represent the targeted six-person contention check.

Do not rely on the old calculated seed volume based on `PROVIDE_DETAILS_JPS=6`.

The current contention simulation only needs a small fixed seed count.

## Formatting

Check formatting:

```bash
sbt scalafmtCheckAll scalafmtCheck
```

Format SBT files:

```bash
sbt scalafmtSbt
```

Format all files:

```bash
sbt scalafmtAll
```

## License

This code is open source software licensed under the Apache 2.0 License.
