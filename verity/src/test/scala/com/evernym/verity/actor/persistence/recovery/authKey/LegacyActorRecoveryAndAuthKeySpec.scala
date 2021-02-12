package com.evernym.verity.actor.persistence.recovery.authKey

import com.evernym.verity.actor.persistence.recovery.base.BaseRecoverySpec
import com.evernym.verity.actor.persistence.recovery.base.eventSetter.legacy.{AgencyAgentEventSetter, UserAgentEventSetter, UserAgentPairwiseEventSetter}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.metrics.CustomMetrics._


//this spec tests that actors with legacy events (without verKeys in persisted events)
// does recover fine and then it fetches ver keys from wallet
// and persists new events with those ver keys so that during next actor recovery
// it doesn't have to use wallet service anymore
class LegacyActorRecoveryAndAuthKeySpec
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

  "When actors started" - {
    "should fetch required keys from wallet service only once" in {
      aaRegion ! GetPersistentActorDetail
      expectMsgType[PersistentActorDetail]
      uaRegion ! GetPersistentActorDetail
      expectMsgType[PersistentActorDetail]
      uapRegion ! GetPersistentActorDetail
      expectMsgType[PersistentActorDetail]

      val walletServiceCountBeforeRestart = getWalletAPICallCount
      val aaEventsBeforeRestart = getEvents(myAgencyAgentPersistenceId)
      val uaEventsBeforeRestart = getEvents(mySelfRelAgentPersistenceId)
      val uapEventsBeforeRestart = getEvents(myPairwiseRelAgentPersistenceId)

      restartAllActors()

      val walletServiceCountAfterRestart = getWalletAPICallCount
      val aaEventsAfterRestart = getEvents(myAgencyAgentPersistenceId)
      val uaEventsAfterRestart = getEvents(mySelfRelAgentPersistenceId)
      val uapEventsAfterRestart = getEvents(myPairwiseRelAgentPersistenceId)

      walletServiceCountAfterRestart shouldBe walletServiceCountBeforeRestart
      aaEventsAfterRestart shouldBe aaEventsBeforeRestart
      uaEventsAfterRestart shouldBe uaEventsBeforeRestart
      uapEventsAfterRestart shouldBe uapEventsBeforeRestart
    }
  }

  def restartAllActors(times: Int = 3): Unit = {
    (1 to times).foreach { _ =>
      restartActor(aaRegion)
      restartActor(uaRegion)
      restartActor(uapRegion)
    }
  }

  def getWalletAPICallCount: Double = {
    Thread.sleep(3000)  //waiting sufficient time so that metrics data gets stabilized
    val walletSucceedApiMetric = getFilteredMetric(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
    walletSucceedApiMetric.isDefined shouldBe true
    walletSucceedApiMetric.map(_.value).getOrElse(-1)
  }

}
