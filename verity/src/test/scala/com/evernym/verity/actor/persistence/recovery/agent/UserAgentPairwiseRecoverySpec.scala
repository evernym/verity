package com.evernym.verity.actor.persistence.recovery.agent

import com.evernym.verity.actor.persistence.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.persistence.recovery.base.{AgencyAgentEventSetter, BaseRecoverySpec, UserAgentEventSetter, UserAgentPairwiseEventSetter}

class UserAgentPairwiseRecoverySpec
   extends BaseRecoverySpec
     with AgencyAgentEventSetter
     with UserAgentEventSetter
     with UserAgentPairwiseEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    setupBasicUserAgentPairwise()
  }

  "UserAgentPairwise actor" - {
    "when sent GetActorDetail" - {
      "should respond with correct detail" in {
        uapRegion ! GetActorDetail
        val ad = expectMsgType[ActorDetail]
        assertActorDetail(ad, myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents.size)
      }
    }
  }

}
