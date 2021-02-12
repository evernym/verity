package com.evernym.verity.actor.persistence.recovery.authKey

import com.evernym.verity.actor.AuthKeyAdded
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoverySpec
import com.evernym.verity.actor.persistence.recovery.base.eventSetter.latest.{AgencyAgentEventSetter, UserAgentEventSetter, UserAgentPairwiseEventSetter}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.metrics.CustomMetrics._

//this spec tests that actors with new events (with verKeys in persisted events)
// doesn't have to go to wallet service during actor recovery
class LatestActorRecoveryAndAuthKeySpec
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

      val aaEventsBeforeStart = getEvents(myAgencyAgentPersistenceId)
      val uaEventsBeforeStart = getEvents(mySelfRelAgentPersistenceId)
      val uapEventsBeforeStart = getEvents(myPairwiseRelAgentPersistenceId)

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

      aaEventsBeforeRestart shouldBe aaEventsBeforeStart
      uaEventsBeforeRestart shouldBe uaEventsBeforeStart
      //in below assertion, the extra event is expected because of legacy connection events
      uapEventsBeforeRestart shouldBe uapEventsBeforeStart ++ List(AuthKeyAdded(theirPairwiseRelDIDPair.DID, theirPairwiseRelDIDPair.verKey))

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
    val walletSucceedApiCallMetric = getFilteredMetric(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
    walletSucceedApiCallMetric.isDefined shouldBe true
    walletSucceedApiCallMetric.map(_.value).getOrElse(-1)
  }

}