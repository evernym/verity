package com.evernym.verity.actor.persistence.recovery.base

import akka.testkit.TestKitBase
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.base.{Ping, Stop}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.PersistentActorDetail
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.metrics.CustomMetrics.AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT
import com.evernym.verity.metrics.TestMetricsBackend
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.language.postfixOps


trait BaseRecoveryActorSpec
  extends BaseRecoverySpecLike
    with ActorSpec
    with BasicSpec
    with Eventually { this: TestKitBase with BasicSpec =>

  //this 'expectDeadLetters' is overridden to ignore some dead letter messages
  // logged during actor restart ('restartActor' function below)
  override def expectDeadLetters = true

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  def restartActor(ar: agentRegion, times: Int = 1): Unit = {
    (1 to times).foreach { _ =>
      restartActor(ar)
    }
  }

  private def restartActor(ar: agentRegion): Unit = {
    ar ! Stop(sendAck = true)
    expectMsgType[Done.type]
    eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
      ar ! Ping(sendAck = true)
      expectMsgType[Done.type]
    }
  }
}

trait BaseRecoverySpecLike
  extends BasePersistentStore
    with Eventually { this: BasicSpec =>

  def testMetricsBackend : TestMetricsBackend

  //TODO: may wanna rework this?
  def getStableWalletAPISucceedCountMetric: Double = {
    var apiCallCount: Double =
      testMetricsBackend.filterGaugeMetrics(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT).headOption.map(_._2).getOrElse(0)
    var timesFoundSameCount: Int = 0    //how many times the metrics count was found same (stable count)
    eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
      val newCount: Double = testMetricsBackend.filterGaugeMetrics(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
        .headOption.map(_._2).getOrElse(0)
      if (apiCallCount == newCount) timesFoundSameCount = timesFoundSameCount + 1
      else timesFoundSameCount = 0
      val isStable = apiCallCount == newCount && timesFoundSameCount >= 5
      apiCallCount = newCount
      isStable shouldBe true
    }
    apiCallCount
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
