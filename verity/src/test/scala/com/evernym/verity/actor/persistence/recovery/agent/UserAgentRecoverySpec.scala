package com.evernym.verity.actor.persistence.recovery.agent

import com.evernym.verity.actor.persistence.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.persistence.recovery.base.{AgencyAgentEventSetter, BaseRecoverySpec, UserAgentEventSetter}

class UserAgentRecoverySpec
   extends BaseRecoverySpec
     with AgencyAgentEventSetter
     with UserAgentEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
  }

  "UserAgent actor" - {
    "when sent GetActorDetail" - {
      "should respond with correct detail" in {
        uaRegion ! GetActorDetail
        val ad = expectMsgType[ActorDetail]
        assertActorDetail(ad, mySelfRelAgentPersistenceId, basicUserAgentEvents.size)
      }
    }
  }

}
