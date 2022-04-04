package com.evernym.verity.event_bus.adapters.basic.event_store

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, handleExceptions, pathPrefix, _}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, ShardUtil}
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.event_bus.adapters.basic.HttpServerParam
import com.evernym.verity.http.HttpUtil
import com.evernym.verity.http.common.CustomExceptionHandler.exceptionHandler
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


//basic event store api (http server, listens for publish/subscribe api request and sends it to appropriate topic actors)
object BasicEventStoreAPI {
  def apply(config: Config)(implicit system: ActorSystem, executionContext: ExecutionContext): BasicEventStoreAPI =
    new BasicEventStoreAPI(config)
}


class BasicEventStoreAPI(config: Config)(implicit system: ActorSystem, executionContext: ExecutionContext)
  extends ShardUtil {

  private val route: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("event-store") {
        extractRequest { implicit req: HttpRequest =>
          extractClientIP { implicit remoteAddress =>
            pathPrefix("topic") {
              pathPrefix(Segment) { topicName =>
                pathPrefix("publish") {
                  (post & pathEnd) {
                    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
                    entity(as[Array[Byte]]) { data =>
                      complete {
                        publishToTopic(topicName, data).map[ToResponseMarshallable] {
                          case Done => OK
                        }
                      }
                    }
                  }
                } ~
                  pathPrefix("subscribe") {
                    (post & pathEnd & HttpUtil.entityAs[SubscribeTopic]) { st =>
                      complete {
                        subscribeToTopic(topicName, st).map[ToResponseMarshallable] {
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

  private def publishToTopic(topicName: String, event: Array[Byte]): Future[Done] = {
    sendToTopicActor(topicName, AddMessage(event))
  }

  private def subscribeToTopic(topicName: String, st: SubscribeTopic): Future[Done] = {
    sendToTopicActor(topicName, AddSubscriber(Subscriber(st.subscriberId, st.webhook)))
  }

  protected def sendToTopicActor(topicName: TopicName, msg: ActorMessage): Future[Done] = {
    val region = getTopicShardedRegion()
    region.ask(ForIdentifier(topicName, msg)).mapTo[Done]
  }

  private def getTopicShardedRegion()(implicit system: ActorSystem): ActorRef = {
    ClusterSharding.get(system).shardRegion(TOPIC_MSG_STORE_REGION_ACTOR_NAME)
  }

  private val listenerParam: HttpServerParam = {
    HttpServerParam(
      config.getString("verity.event-bus.basic.store.http-listener.host"),
      config.getInt("verity.event-bus.basic.store.http-listener.port")
    )
  }

  def stop(): Future[Done] = {
    bindingFut.flatMap(_.terminate(25.seconds)).map(_ => Done)
  }

  private val TOPIC_MSG_STORE_REGION_ACTOR_NAME = "topic-msg-store"

  val appConfig: AppConfigWrapper = new AppConfigWrapper() {
    config = config
  }
  lazy val logger: Logger = getLoggerByClass(getClass)
  implicit val timeout: Timeout = Timeout(30.seconds)

  type TopicName = String

  //TopicMsgStore actor will be sharded based on the topic name
  createNonPersistentRegion(
    TOPIC_MSG_STORE_REGION_ACTOR_NAME,
    buildProp(TopicMsgStore.props()),
    passivateIdleEntityAfter = Option(600.seconds)
  )

  private val bindingFut = Http().newServerAt(listenerParam.host, listenerParam.port).bind(route)
}

case class Subscriber(id: String, webhook: String)

case class SubscribeTopic(subscriberId: String, webhook: String)