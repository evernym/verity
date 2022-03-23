package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorRef
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.event_bus.ports.consumer.{Message, MessageHandler}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

//all consumed messages should come to this message handler and then it will decide what to do with it
class ConsumedMessageHandler(config: Config,
                             agentMsgRouter: AgentMsgRouter,
                             singletonParentProxy: ActorRef)(implicit executionContext: ExecutionContext)
  extends MessageHandler {

  val logger: Logger = getLoggerByClass(getClass)

  lazy val endorserRegistryEventHandler = new EndorserMessageHandler(config, singletonParentProxy)
  lazy val endorsementReqStatusMessageHandler = new EndorsementMessageHandler(config, agentMsgRouter)

  override def handleMessage(message: Message): Future[Done] = {
    message.metadata.topic match {
      case TOPIC_SSI_ENDORSER =>
        endorserRegistryEventHandler.handleMessage(message)

      case TOPIC_SSI_ENDORSEMENT =>
        endorsementReqStatusMessageHandler.handleMessage(message)

      case _ =>
        logger.info(s"unhandled consumed event: " + message.metadata)
        Future.successful(Done)
    }
  }
}
