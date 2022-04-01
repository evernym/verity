package com.evernym.verity.event_bus.adapters.basic.consumer

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.{ActorMessage, EventConsumerAdapterBuilder}
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.config.AppConfig
import com.evernym.verity.event_bus.adapters.basic.{BaseEventAdapter, RegisterConsumer}
import com.evernym.verity.event_bus.event_handlers.ConsumedMessageHandler
import com.evernym.verity.event_bus.ports.consumer.{ConsumerPort, Message, MessageHandler, Metadata}
import org.json.JSONObject

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}


class BasicConsumerAdapterBuilder
  extends EventConsumerAdapterBuilder {

  override def build(appConfig: AppConfig,
                     agentMsgRouter: AgentMsgRouter,
                     singletonParentProxy: ActorRef,
                     executionContext: ExecutionContext,
                     actorSystem: ActorSystem): ConsumerPort = {
    val messageHandler = new ConsumedMessageHandler(
      appConfig.config,
      agentMsgRouter, singletonParentProxy)(executionContext)
    new BasicConsumerAdapter(appConfig, messageHandler)(actorSystem)
  }
}

class BasicConsumerAdapter(val appConfig: AppConfig,
                           val messageHandler: MessageHandler)(implicit system: ActorSystem)
  extends ConsumerPort
    with BaseEventAdapter
    with CoreActorExtended {

  override def start(): Unit = {
    val topicNames = appConfig.getStringListReq("verity.kafka.consumer.topics")
    topicNames.foreach { tn =>
      sendToTopicActor(tn, RegisterConsumer(self))
    }
  }

  override def stop(): Future[Done] = {
    stopActor()
    Future.successful(Done)
  }

  override def receiveCmd: Receive = {
    case ConsumedEvent(topicName, payload) =>
      val createTime = Instant.now()
      val metadata = Metadata(topicName, 0, 0, createTime)
      val cloudEvent = new JSONObject(new String(payload))
      val message = Message(metadata, cloudEvent)
      messageHandler.handleMessage(message)
  }
}

case class ConsumedEvent(topicName: String, payload: Array[Byte]) extends ActorMessage