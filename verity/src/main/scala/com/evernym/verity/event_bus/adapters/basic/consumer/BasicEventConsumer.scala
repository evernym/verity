package com.evernym.verity.event_bus.adapters.basic.consumer

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.{ActorMessage, EventConsumerAdapterBuilder}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.event_bus.adapters.basic.event_store.SubscribeTopic
import com.evernym.verity.event_bus.adapters.basic.{BaseEventAdapter, HttpServerParam}
import com.evernym.verity.event_bus.event_handlers.ConsumedMessageHandler
import com.evernym.verity.event_bus.ports.consumer.{ConsumerPort, Message, MessageHandler, Metadata}
import com.evernym.verity.http.common.CustomResponseHandler.exceptionHandler
import org.json.JSONObject

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag


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
    new BasicConsumerAdapter(appConfig, messageHandler)(actorSystem, executionContext)
  }
}

class BasicConsumerAdapter(val appConfig: AppConfig,
                           val messageHandler: MessageHandler)
                          (implicit system: ActorSystem, executionContext: ExecutionContext)
  extends ConsumerPort
    with BaseEventAdapter {

  override def start(): Future[Done] = {
    val topicNames = appConfig.getStringListReq("verity.event-bus.basic.consumer.topics")
    Future.traverse(topicNames) { tn =>
      subscribeToTopic(tn, SubscribeTopic(subscriberId, listenerParam.url + s"/event-consumer/topic/$tn"))
    }.map(_ => Done)
  }

  private def subscribeToTopic[T: ClassTag](topicName: String, payload: T)
                                           (implicit system: ActorSystem, executionContext: ExecutionContext): Future[HttpResponse] = {
    val json = DefaultMsgCodec.toJson(payload)
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = eventStoreParam.url + s"/event-store/topic/$topicName/subscribe",
      entity = HttpEntity(ContentTypes.`application/json`, json)
    )
    postHttpRequest(request)
  }

  override def stop(): Future[Done] = {
    httpServerBinding.map(_.terminate(25.seconds)).map(_ => Done)
  }

  private val route: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("event-consumer") {
        extractRequest { implicit req: HttpRequest =>
          extractClientIP { implicit remoteAddress =>
            pathPrefix("topic") {
              pathPrefix(Segment) { topicName =>
                (post & pathEnd) {
                  import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
                  entity(as[Array[Byte]]) { data =>
                    complete {
                      handleEvent(topicName, data).map[ToResponseMarshallable] {
                        case Done => OK
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

  private def handleEvent(topicName: String, payload: Array[Byte]): Future[Done] = {
    val createTime = Instant.now()
    val metadata = Metadata(topicName, 0, 0, createTime)
    val cloudEvent = new JSONObject(new String(payload))
    val message = Message(metadata, cloudEvent)
    messageHandler.handleMessage(message)
  }

  private val subscriberId: String = appConfig.getStringReq("verity.event-bus.basic.consumer.id")
  private val listenerParam: HttpServerParam = {
    HttpServerParam(
      appConfig.getStringReq("verity.event-bus.basic.consumer.http-listener.host"),
      appConfig.getIntReq("verity.event-bus.basic.consumer.http-listener.port")
    )
  }

  val httpServerBinding: Future[Http.ServerBinding] = Http().newServerAt(listenerParam.host, listenerParam.port).bind(route)
}

case class ConsumedEvent(topicName: String, payload: Array[Byte]) extends ActorMessage