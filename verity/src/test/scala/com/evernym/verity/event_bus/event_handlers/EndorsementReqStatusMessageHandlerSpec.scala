package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.config.AppConfig
import com.evernym.verity.event_bus.ports.consumer.{Message, Metadata}
import com.evernym.verity.protocol.engine.PinstId
import com.evernym.verity.util2.RouteId

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


class EndorsementReqStatusMessageHandlerSpec
  extends EventHandlerSpecBase {

  val requestSourceStr: String = createSource("routeId", "write-schema", "0.6", "pinstId1")


  "EndorserReqStatusMessageHandler" - {
    "when received EndorserComplete cloud event message" - {
      "should handle it successfully" in {

        val fut = endorserReqStatusEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_ENDORSEMENT_REQ_STATUS, partition = 1, offset = 0, Instant.now()),
            toJsonObject(
              createCloudEvent(
                TYPE_ENDORSEMENT_COMPLETE,
                "https://endorsment.com",
                s"""{"request_source":"$requestSourceStr", "result":"100", "result_descr":"successful"}"""
              )
            )
          )
        )
        Await.result(fut, 500.seconds)
      }
    }
  }

  def createSource(routeId: RouteId, protocol: String, version: String, pinstId: PinstId): String =
    s"http://verity.avast.com/route/$routeId/protocol/$protocol/version/$version/pinstid/$pinstId"

  lazy val agentMsgRouter = new MockAgentMsgRouter(appConfig, system, executionContext)

  lazy val endorserReqStatusEventHandler = new EndorsementReqStatusMessageHandler(
    appConfig.config, agentMsgRouter)(executionContextProvider.futureExecutionContext)

}


class MockAgentMsgRouter(appConfig: AppConfig,
                         system: ActorSystem,
                         executionContext: ExecutionContext)
  extends AgentMsgRouter(executionContext)(appConfig, system) {

  override def execute: PartialFunction[Any, Future[Any]] = {
    case cmd: Any => Future.successful(Done)
  }
}