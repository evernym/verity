package com.evernym.integrationtests.e2e.flow

import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}
import com.evernym.verity.testkit.BasicSpec



trait VerityConfigFlow {
  this: BasicSpec =>

  def reloadConfig(app: ApplicationAdminExt)(implicit scenario: Scenario): Unit = {
    app.reloadConfig()
  }

  def checkConfig(app: ApplicationAdminExt, path: String, expectedValue: String)(implicit scenario: Scenario): Unit = {
    app.getConfigAtPath(path) shouldBe expectedValue
  }
}
