package com.evernym.verity.actor.agent.user

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, HasAppConfig, MsgStatusUpdated, ShardUtil}
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.base.{Done, Ping, Stop}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.Status
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ExecutionContext, Future}


class FailedMsgRetrierSpec
  extends ActorSpec
    with BasicSpec
    with ShardUtil
    with Eventually
    with BeforeAndAfterAll {

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  lazy val mockAgentRegion: ActorRef = createPersistentRegion(MockAgentActor.name, MockAgentActor.props(appConfig, ecp.futureExecutionContext))

  override def beforeAll(): Unit = {
    val _ = platform.singletonParentProxy
    super.beforeAll()
  }

  "AgentActor" - {

    "when started" - {
      "should NOT register itself with watcher" in {
        EventFilter.debug(pattern = "item added to watcher: .*", occurrences = 0) intercept {
          sendToMockActor(Ping(sendAck = true))
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
        EventFilter.debug(pattern = "item added to watcher: .*", occurrences = 2) intercept {
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
        sendToMockActor(Stop(sendAck = true))
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
         akka.logging-filter = "com.evernym.verity.actor.testkit.logging.TestFilter"
      """
    )
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}

class MockAgentActor(val appConfig: AppConfig, executionContext: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with FailedMsgRetrier
    with HasAppConfig
    with ActorLogging {

  def agentCmd: Receive = {
    case um: UpdateMsgDeliveryStatus =>
      if (um.isFailed) {
        writeAndApply(MsgStatusUpdated(um.uid, Status.MSG_DELIVERY_STATUS_FAILED.statusCode))
      }
      sender() ! Done

    case GetPending => sender() ! PendingMsgs(pendingMsgs)
  }

  override def receiveCmd: Receive = agentCmd orElse retryCmdReceiver

  override def receiveEvent: Receive = {
    case msu: MsgStatusUpdated => pendingMsgs += msu.uid
  }

  var pendingMsgs = Set.empty[String]

  override def scheduledJobInterval: Int = 2
  override def getMsgIdsEligibleForRetries: Set[MsgId] = pendingMsgs
  override def msgPackFormat(msgId: MsgId): MsgPackFormat = MPF_INDY_PACK

  override def sendMsgToTheirAgent(uid: MsgId, isItARetryAttempt: Boolean, mpf: MsgPackFormat): Future[Any] = {
    pendingMsgs -= uid
    Future.successful(Done)
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

object MockAgentActor {
  def name = "mock-agent-actor"
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props = Props(new MockAgentActor(appConfig, executionContext))
}

case object GetPending extends ActorMessage
case class PendingMsgs(msgs: Set[MsgId]) extends ActorMessage
