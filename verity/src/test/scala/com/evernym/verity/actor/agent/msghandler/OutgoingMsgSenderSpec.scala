package com.evernym.verity.actor.agent.msghandler

import akka.actor.{ActorRef, Props}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.{MsgSendingFailed, MsgSentSuccessfully}
import com.evernym.verity.actor.agent.msghandler.outgoing.{HasOutgoingMsgSender, JsonMsg, OutgoingMsgParam, ProcessSendMsgToMyDomain, ProcessSendMsgToTheirDomain}
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.AKKA_SHARDING_REGION_NAME_USER_AGENT
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.reflect.ClassTag


class OutgoingMsgSenderSpec
  extends BasicSpec
    with PersistentActorSpec
    with Eventually {

  lazy val agentActor: ActorRef = system.actorOf(MockAgentActor.props(appConfig, self))

  "OutgoingMsgSender" - {
    "when asked to SendMsgToMyDomain" - {

      "if it is delivered successfully" - {
        "should respond accordingly" in {
          val omp = OutgoingMsgParam(JsonMsg("msg"), None)
          agentActor ! SendMsgToMyDomain(
            omp,
            "msgId1",
            "msgType",
            "senderDID",
            None)
          expectMsgType[ProcessSendMsgToMyDomain]
          checkOutgoingMsgSenderActor("msgId1", shallExists = false)
        }
      }

      "if delivery fails" - {
        "should respond accordingly" in {
          agentActor ! SetNextDeliveryAttemptAsFailed

          val omp = OutgoingMsgParam(JsonMsg("msg"), None)
          agentActor ! SendMsgToMyDomain(
            omp,
            "msgId2",
            "msgType",
            "senderDID",
            None)
          expectMsgType[ProcessSendMsgToMyDomain]
          checkOutgoingMsgSenderActor("msgId2", shallExists = true)
          (1 to 3).foreach { _ =>
            checkRetryAttempt[ProcessSendMsgToMyDomain]()
          }
          checkOutgoingMsgSenderActor("msgId2", shallExists = false)
        }
      }
    }

    "when asked to SendMsgToTheirDomain" - {

      "if it is delivered successfully" - {
        "should respond accordingly" in {
          agentActor ! SetNextDeliveryAttemptAsPassed
          val omp = OutgoingMsgParam(JsonMsg("msg"), None)
          agentActor ! SendMsgToTheirDomain(
            omp,
            "msgId3",
            "msgType",
            "senderDID",
            None)
          expectMsgType[ProcessSendMsgToTheirDomain]
          checkOutgoingMsgSenderActor("msgId2", shallExists = false)
        }
      }

      "if delivery fails" - {
        "should respond accordingly" in {
          agentActor ! SetNextDeliveryAttemptAsFailed
          val omp = OutgoingMsgParam(JsonMsg("msg"), None)
          agentActor ! SendMsgToTheirDomain(
            omp,
            "msgId4",
            "msgType",
            "senderDID",
            None)
          expectMsgType[ProcessSendMsgToTheirDomain]

          checkOutgoingMsgSenderActor("msgId4", shallExists = true)
          (1 to 3).foreach { _ =>
            checkRetryAttempt[ProcessSendMsgToTheirDomain]()
          }
          checkOutgoingMsgSenderActor("msgId4", shallExists = false)
        }
      }
    }
  }

  def checkRetryAttempt[T: ClassTag](): Unit = {
    eventually(timeout(Span(20, Seconds)), interval(Span(200, Millis))) {
      expectMsgType[T]
    }
  }

  def checkOutgoingMsgSenderActor(msgId: MsgId, shallExists: Boolean): Unit = {
    eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
      agentActor ! IsChildActorExists(msgId)
      expectMsg(shallExists)
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class MockAgentActor(appConfig: AppConfig, caller: ActorRef)
  extends CoreActorExtended
    with HasOutgoingMsgSender {

  override val maxRetryAttempt: Int = 3
  override val initialDelayInSeconds: Int = 1

  var failNextDeliveryAttempt = false

  override def receiveCmd: Receive = {
    case sm: SendMsgToMyDomain              => forwardToOutgoingMsgSender(sm.msgId, sm)
    case sm: SendMsgToTheirDomain           => forwardToOutgoingMsgSender(sm.msgId, sm)

    case IsChildActorExists(msgId)          => sender ! context.child(msgId).isDefined
    case SetNextDeliveryAttemptAsFailed     => failNextDeliveryAttempt = true
    case SetNextDeliveryAttemptAsPassed     => failNextDeliveryAttempt = false

    case psm: ProcessSendMsgToMyDomain      =>
      caller ! psm    //for assertion purposes
      if (failNextDeliveryAttempt) {
        forwardToOutgoingMsgSenderIfExists(psm.msgId, MsgSendingFailed(psm.msgId, psm.msgName))
      } else {
        forwardToOutgoingMsgSenderIfExists(psm.msgId, MsgSentSuccessfully(psm.msgId, psm.msgName))
      }

    case pst: ProcessSendMsgToTheirDomain   =>
      caller ! pst    //for assertion purposes
      if (failNextDeliveryAttempt) {
        forwardToOutgoingMsgSenderIfExists(pst.msgId, MsgSendingFailed(pst.msgId, pst.msgName))
      } else {
        forwardToOutgoingMsgSenderIfExists(pst.msgId, MsgSentSuccessfully(pst.msgId, pst.msgName))
      }
  }

  lazy val isVAS: Boolean =
    appConfig
      .getStringOption(AKKA_SHARDING_REGION_NAME_USER_AGENT)
      .contains("VerityAgent")
}

object MockAgentActor {
  def props(appConfig: AppConfig, caller: ActorRef): Props = Props(new MockAgentActor(appConfig, caller))
}

case object SetNextDeliveryAttemptAsFailed extends ActorMessage
case object SetNextDeliveryAttemptAsPassed extends ActorMessage
case class IsChildActorExists(msgId: MsgId) extends ActorMessage
