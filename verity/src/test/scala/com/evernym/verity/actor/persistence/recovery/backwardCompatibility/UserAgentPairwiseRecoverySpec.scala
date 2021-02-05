package com.evernym.verity.actor.persistence.recovery.backwardCompatibility

import com.evernym.verity.actor.persistence.recovery.base.{AgencyAgentEventSetter, BaseRecoverySpec, UserAgentEventSetter, UserAgentPairwiseEventSetter}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}


/**
 * This tests the scenario wherein old (created before Sep 2019) user agent pairwise actor
 * who didn't used to store their (other side of the connection) key into wallet
 * should be able to spin up (recover from events) successfully
 * (this is related to VE-2347)
 *
 */
class UserAgentPairwiseRecoverySpec
   extends BaseRecoverySpec
     with AgencyAgentEventSetter
     with UserAgentEventSetter
     with UserAgentPairwiseEventSetter {

  override val addTheirPairwiseKeyInWallet = false

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
