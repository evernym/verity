package com.evernym.integrationtests.e2e.flow

import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.util.AssertionUtil.expectMsgType
import com.evernym.verity.testkit.util.LedgerUtil
import com.typesafe.scalalogging.Logger


trait SetupFlow {
  this: BasicSpec =>

  val logger: Logger = getLoggerByName(getClass.getName)

  def setupApplication(app: ApplicationAdminExt, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    s"setting up ${app.name}" - {
      s"[${app.name}] check that application is listening" in {
        app.checkIfListening()
      }

      s"[${app.name}] should be able to successfully setup verity" taggedAs (UNSAFE_IgnoreLog) in {
        if (app.instance.setup) {
          logger.info(s"local '${app.name}' instance setup process started...")
          app.agencyIdentity = expectMsgType[AgencyPublicDid](app.setupAgencyKey(seedOpt = app.instance.seed))
          app.setupAgencyKeyRepeated()
          ledgerUtil.bootstrapNewDID(app.agencyIdentity.DID, app.agencyIdentity.verKey)
          app.setupAgencyEndpoint()
          app.setupAgencyEndpointRepeated()
          logger.info(s"local '${app.name}' instance setup process finished")
        } else {
          logger.info(s"remote '${app.name}' instance setup process assumed to be already done")
        }

        if (app.instance.messageTrackingEnabled.getOrElse(false)) {
          app.setupStartMessageTracking(app.instance.messageTrackingById.getOrElse(MsgProgressTrackerCache.GLOBAL_TRACKING_ID))
        }
      }
    }
  }
}
