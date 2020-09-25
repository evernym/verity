package com.evernym.integrationtests.e2e.flow

import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.util.AssertionUtil.expectMsgType
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}


trait SetupFlow {
  this: BasicSpec =>

  def setupApplication(app: ApplicationAdminExt, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    s"setting up ${app.name}" - {
      s"[${app.name}] check that application is listening" in {
        app.checkIfListening()
      }

      s"[${app.name}] should be able to successfully setup verity" taggedAs (UNSAFE_IgnoreLog) in {
        if (app.instance.setup) {
          println(s"local '${app.name}' instance setup process started...")
          app.agencyIdentity = expectMsgType[AgencyPublicDid](app.setupAgencyKey(seedOpt = app.instance.seed))
          app.setupAgencyKeyRepeated()
          ledgerUtil.bootstrapNewDID(app.agencyIdentity.DID, app.agencyIdentity.verKey)
          app.setupAgencyEndpoint()
          app.setupAgencyEndpointRepeated()
          println(s"local '${app.name}' instance setup process finished")
        } else {
          println(s"remote '${app.name}' instance setup process assumed to be already done")
        }

        if (app.instance.messageTrackingEnabled.getOrElse(false)) {
          app.setupStartMessageTracking(app.instance.messageTrackingById.getOrElse("global"))
        }
      }
    }
  }
}
