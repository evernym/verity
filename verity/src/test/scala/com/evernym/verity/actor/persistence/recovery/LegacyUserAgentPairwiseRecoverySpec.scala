package com.evernym.verity.actor.persistence.recovery

import com.evernym.verity.actor.base.{Done, Ping}
import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.recovery.base.AgentEventSetter

class LegacyUserAgentPairwiseRecoverySpec
  extends AgentEventSetter {

  def uap: agentRegion = agentRegion(myPairwiseRelAgentEntityId, userAgentPairwiseRegionActor)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    setupBasicUserAgentPairwiseWithLegacyEvents()
  }

  "UserAgentPairwise actor" - {
    "when try to recover with legacy events" - {
      "should be able to successfully recovered" in {
        uap ! Ping(sendBackConfirmation = true)
        expectMsgType[Done.type]    //successful response means actor recovered successfully
      }
    }
  }

}
