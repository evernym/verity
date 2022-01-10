package com.evernym.verity.actor.persistence.recovery.legacy.verity1.specific

import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.persistence.recovery.legacy.verity1.{AgencyAgentEventSetter, UserAgentEventSetter, UserAgentPairwiseEventSetter}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}

import scala.concurrent.ExecutionContext


/**
 * This tests the scenario wherein old (created before Sep 2019) user agent pairwise actor
 * who didn't used to store their (other side of the connection) key into wallet,
 * should be able to spin up (recover from events) successfully
 * (this is related to VE-2347)
 *
 */
class UserAgentPairwiseBeforeSep2019RecoverySpec
   extends BaseRecoveryActorSpec
     with AgencyAgentEventSetter
     with UserAgentEventSetter
     with UserAgentPairwiseEventSetter {

  //this is to reproduce the case as mentioned in above comment
  override val addTheirPairwiseKeyInWallet = false

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    setupBasicUserAgentPairwise()
    closeClientWallets(Set(myAgencyAgentEntityId, mySelfRelAgentEntityId))
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

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
