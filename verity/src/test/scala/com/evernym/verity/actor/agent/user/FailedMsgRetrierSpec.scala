package com.evernym.verity.actor.agent.user

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.testkit.EventFilter
import ch.qos.logback.classic.Level
import com.evernym.verity.Status
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, ItemUpdated, ShardUtil}
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.base.{Done, Ping, Stop}
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, VersionId}
import com.evernym.verity.actor.itemmanager.ItemContainerMapper
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.testkit.{ActorSpec, WithAdditionalLogs}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future


class FailedMsgRetrierSpec
  extends ActorSpec
    with BasicSpec
    with ShardUtil
    with WithAdditionalLogs
    with Eventually
    with BeforeAndAfterAll {

  override def toLevel: Level = Level.DEBUG
  lazy val mockAgentRegion: ActorRef = createPersistentRegion(MockAgentActor.name, MockAgentActor.props(appConfig))

  override def beforeAll(): Unit = {
    val _ = platform.singletonParentProxy
    super.beforeAll()
  }

  "AgentActor" - {

    "when started" - {
      "should NOT register itself with watcher" in {
        EventFilter.debug(pattern = "item added to watcher: .*", occurrences = 0) intercept {
          sendToMockActor(Ping(sendBackConfirmation = true))
          expectMsgType[Done.type]
        }
      }
    }

    "when received delivery status message with status SENT" - {
      "should NOT register itself with watcher" in {
        EventFilter.debug(pattern = "item added to watcher: .*", occurrences = 0) intercept {
          sendToMockActor(UpdateMsgDeliveryStatus("msg-id-1", "to", Status.MSG_DELIVERY_STATUS_SENT.statusCode, None))
          expectMsgType[Done.type]
        }
      }
    }

    "when received delivery status message with status FAILED" - {
      "should register itself with watcher" in {
        //doesn't matter how many times a failure occur for same message or different messages,
        // that actor should be registered only once
        EventFilter.debug(pattern = "item added to watcher: .*", occurrences = 1) intercept {
          sendToMockActor(UpdateMsgDeliveryStatus("msg-id-1", "to", Status.MSG_DELIVERY_STATUS_FAILED.statusCode, None))
          expectMsgType[Done.type]
          sendToMockActor(UpdateMsgDeliveryStatus("msg-id-2", "to", Status.MSG_DELIVERY_STATUS_FAILED.statusCode, None))
          expectMsgType[Done.type]
        }
      }
    }

    "when sent Stop message" - {
      "should be stopped" in {
        //stopping agent actor to confirm it will be spinned up by the watcher actor
        // because it was registered in watcher
        sendToMockActor(Stop(sendBackConfirmation = true))
        expectMsgType[Done.type]
      }
    }

    "when checked if failed messages being retried or not" - {
      "should confirm that failed messages gets retried" in {
        EventFilter.debug(pattern = "send msg to their agent: .*", occurrences = 2) intercept {
          //nothing to do
        }
      }
    }

    "when checked if the agent actor finally gets de-registered" - {
      "should be de-registered successfully" in {
        EventFilter.debug(pattern = "item removed from watcher: 1", occurrences = 1) intercept {
          //nothing to do
        }
      }
    }

  }

  def sendToMockActor(cmd: Any): Unit = {
    sendToMockActor("1", cmd)
  }

  def sendToMockActor(toEntityId: String, cmd: Any): Unit = {
    mockAgentRegion ! ForIdentifier(toEntityId, cmd)
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString(
      s"""
         verity.actor.watcher {
           scheduled-job {
             interval-in-seconds = 2
           }
         }
         verity.interval-in-seconds.scheduled-job.interval-in-seconds = 2

         akka.loglevel = DEBUG
         akka.test.filter-leeway = 20s   # to make the event filter run for longer time
      """
    )
  }
}

class MockAgentActor(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with FailedMsgRetrier
    with HasAppConfig
    with ActorLogging {

  def agentCmd: Receive = {
    case um: UpdateMsgDeliveryStatus =>
      if (um.isFailed) {
        writeAndApply(ItemUpdated(um.uid))
      }
      sender ! Done

    case GetPending => sender ! PendingMsgs(pendingMsgs)
  }

  override def receiveCmd: Receive = agentCmd orElse retryCmdReceiver

  override def receiveEvent: Receive = {
    case iu: ItemUpdated => pendingMsgs += iu.id
  }

  var pendingMsgs = Set.empty[String]

  override def scheduledJobInterval: Int = 2
  override def getMsgIdsEligibleForRetries: Set[MsgId] = pendingMsgs
  override def msgPackFormat(msgId: MsgId): MsgPackFormat = MPF_INDY_PACK

  override def sendMsgToTheirAgent(uid: MsgId, isItARetryAttempt: Boolean, mpf: MsgPackFormat): Future[Any] = {
    pendingMsgs -= uid
    Future.successful(Done)
  }

}

object MockAgentActor {
  def name = "mock-agent-actor"
  def props(appConfig: AppConfig): Props = Props(new MockAgentActor(appConfig))
}

case object GetPending extends ActorMessage
case class PendingMsgs(msgs: Set[MsgId]) extends ActorMessage

case class MockTimeBasedItemContainerMapper(versionId: VersionId) extends ItemContainerMapper {

  def getItemContainerId(itemId: ItemId): ItemContainerEntityId = {
    val ldTime = getCurrentUTCZonedDateTime
    val hourBlock = ldTime.getHour.toString.reverse.padTo(2, '0').reverse
    val minuteBlock = ldTime.getMinute.toString.reverse.padTo(2, '0').reverse
    val secondBlock = (0 to 59).grouped(10).toList.zipWithIndex
      .find { case (r, _) => r.contains(ldTime.getMinute) }
      .map(_._2).getOrElse(-1)
    val paddedMonth = ldTime.getMonthValue.toString.reverse.padTo(2, '0').reverse
    val paddedDay = ldTime.getDayOfMonth.toString.reverse.padTo(2, '0').reverse
    s"${ldTime.getYear}$paddedMonth$paddedDay-" + hourBlock + minuteBlock + secondBlock.toString.reverse.padTo(2, '0').reverse
  }
}
