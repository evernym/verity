package com.evernym.verity.actor.persistence.recovery.mixed

import com.evernym.verity.actor.TestJournal
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.persistence.recovery.latest.verity2.vas.ProtocolActorEventSetter
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class ActorRecoveryFailureSupervisorSpec
  extends BaseRecoveryActorSpec
  with ProtocolActorEventSetter {

  override def protoName: String = "connections-1.0-protocol"
  override def protoEntityId: String = "123"

  "ProtocolActor" - {
    "when started" - {
      "should handle recovery failure" in {
        paRegion ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail](360.seconds)
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext

  override def overrideSpecificConfig: Option[Config] = Option {
    ConfigFactory.parseString(
      """
        |verity.persistent-actor.protocol-container.supervisor {
        |  enabled = true
        |  strategy = OnFailure
        |  min-seconds = 1
        |  max-seconds = 360
        |  random-factor = 0.2
        |  max-nr-of-retries = 10
        |}
        |""".stripMargin)
      .withFallback(configForReplayEventFailure)
  }

  def configForReplayEventFailure: Config =  {
    AkkaTestBasic.customJournal("com.evernym.verity.actor.persistence.recovery.mixed.FailsOnRecoveryTestJournal")
  }
}


class FailsOnRecoveryTestJournal extends TestJournal {
  var recoveryExceptionThrownCount = Map.empty[String, Int]

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val newCount = recoveryExceptionThrownCount.getOrElse(persistenceId, 0) + 1
    recoveryExceptionThrownCount += persistenceId -> newCount
    if (persistenceId.startsWith("connections-1.0-protocol") && newCount <= 2) {
      Future.failed(new RuntimeException(s"error while reading highest sequence number ($newCount)"))
    } else {
      Future.successful(0L)
    }
  }
}