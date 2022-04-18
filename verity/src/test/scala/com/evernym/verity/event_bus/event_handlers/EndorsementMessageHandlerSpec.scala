package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.config.AppConfig
import com.evernym.verity.event_bus.ports.consumer.{Message, Metadata}
import com.evernym.verity.protocol.engine.{DomainId, PinstId, ProtoRef, RelationshipId, ThreadId}

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


class EndorsementMessageHandlerSpec
  extends EventHandlerSpecBase {

  val requestSourceStr: String = createSource("domainId", "relationshipId", "pinstId1", "threadId", "write-schema", "0.6")

  "EndorserReqStatusMessageHandler" - {
    "when received EndorserComplete cloud event message" - {
      "should handle it successfully" in {

        val fut = endorserReqStatusEventHandler.handleMessage(
          Message(
            Metadata(TOPIC_SSI_ENDORSEMENT, partition = 1, offset = 0, Instant.now()),
            toJsonObject(
              createCloudEvent(
                EVENT_ENDORSEMENT_COMPLETE_V1,
                "https://endorsment.com",
                s"""{"requestsource":"$requestSourceStr", "result": {"code":"100", "descr":"successful"}}"""
              )
            )
          )
        )
        Await.result(fut, 500.seconds)
      }
    }
  }

  def createSource(domainId: DomainId,
                   relationshipId: RelationshipId,
                   threadId: ThreadId,
                   pinstId: PinstId,
                   protocol: String, version: String): String =
    RequestSourceUtil.build(domainId, relationshipId, pinstId, threadId, ProtoRef(protocol, version))

  lazy val agentMsgRouter = new MockAgentMsgRouter(appConfig, system, executionContext)

  lazy val endorserReqStatusEventHandler = new EndorsementMessageHandler(
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