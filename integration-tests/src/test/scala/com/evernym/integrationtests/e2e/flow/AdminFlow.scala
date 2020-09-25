package com.evernym.integrationtests.e2e.flow

import com.evernym.verity.testkit.BasicSpec
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}

trait AdminFlow {
  this: BasicSpec =>
  def fetchAgencyDetail(aae: ApplicationAdminExt)(implicit scenario: Scenario): Unit = {
    s"fetch agency detail from ${aae.name}" - {
      s"[${aae.name}] should be able to fetch agency detail" in {
        aae.fetchAgencyKey()
      }
    }
  }

}
