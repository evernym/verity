package com.evernym.verity.actor.persistence.recovery.base

import akka.testkit.TestKitBase
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.base.{Ping, Stop}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.PersistentActorDetail
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.metrics.CustomMetrics.AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT
import com.evernym.verity.testkit.{AddMetricsReporter, BasicSpec}
import org.scalatest.concurrent.Eventually

trait BaseRecoveryActorSpec
  extends BaseRecoverySpecLike
    with ActorSpec
    with BasicSpec
    with Eventually { this: TestKitBase with BasicSpec =>

  def restartActor(ar: agentRegion, times: Int = 3): Unit = {
    (1 to times).foreach { _ =>
      restartActor(ar)
    }
  }

  private def restartActor(ar: agentRegion): Unit = {
    ar ! Stop(sendBackConfirmation = true)
    expectMsgType[Done.type]
    Thread.sleep(2000)
    ar ! Ping(sendBackConfirmation = true)
    expectMsgType[Done.type]
  }
}

trait BaseRecoverySpecLike
  extends BasePersistentStore
    with AddMetricsReporter { this: BasicSpec =>

  def getWalletAPICallCount: Double = {
    Thread.sleep(3000)  //waiting sufficient time so that metrics data gets stabilized
    val walletSucceedApiMetric = getFilteredMetric(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
    walletSucceedApiMetric.map(_.value).getOrElse(0)
  }

  /**
   * closes client/test side of the wallets
   * @param walletIds
   */
  def closeClientWallets(walletIds: Set[String]): Unit = {
    walletIds.foreach { walletId =>
      closeWallet(walletId)
    }
  }

  def assertPersistentActorDetail(pad: PersistentActorDetail,
                                  expectedPersistenceId: PersistenceIdParam,
                                  expectedRecoveredEvents: Int): Unit = {
    assertPersistentActorDetail(pad, expectedPersistenceId.toString, expectedRecoveredEvents)
  }

  def assertPersistentActorDetail(pad: PersistentActorDetail,
                                  expectedPersistenceId: String,
                                  expectedRecoveredEvents: Int): Unit = {

    //NOTE: this below persistence Id check should NEVER fail
    // if fails, means some logic around how 'persistenceId' is calculated is changed in
    // main code (mostly in BasePersistentActor or its super class)
    // and that means it won't be able to recover previously persisted actor anymore.
    // so we should either revert back the related main code code changes
    // or it should be discussed with team and then decide how to move forward.
    pad.persistenceId shouldBe expectedPersistenceId
    pad.totalRecoveredEvents shouldBe expectedRecoveredEvents
  }

}
