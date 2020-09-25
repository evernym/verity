package com.evernym.integrationtests.e2e.scenario

import com.evernym.verity.testkit.BasicSpec


class ScenarioSpec extends BasicSpec {
  "isRunScenario" - {
    "finds two Scenarios" in {
      Scenario.isRunScenario("vcxFlow", map = Map(Scenario.scenarioEnvKey->"scenario1,vcxFlow")) shouldBe true
      Scenario.isRunScenario("vcxFlow", map = Map(Scenario.scenarioEnvKey->"scenario1")) shouldBe false
    }
    "noEnvVar maps correctly" in {
      Scenario.isRunScenario("vcxFlow", noEnvVar = true, map = Map()) shouldBe true
      Scenario.isRunScenario("vcxFlow", noEnvVar = false, map = Map()) shouldBe false
    }
  }

}
