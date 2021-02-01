package com.evernym.verity.actor.persistence.recovery.agent

import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
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
        uapRegion ! GetPersistentActorDetail
        val ad = expectMsgType[PersistentActorDetail]
        assertPersistentActorDetail(ad, myPairwiseRelAgentPersistenceId, basicUserAgentPairwiseEvents.size)
      }
    }
  }

}
