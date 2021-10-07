package com.evernym.verity.http.route_handlers.restricted

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.pattern.StatusReply.Success
import com.evernym.verity.http.common.CustomExceptionHandler.{exceptionHandler, handleExpectedResponse, handleUnexpectedResponse}
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.Outbox

import scala.concurrent.Future

// todo: should be switched to OutboxService usage after Outbox work finalization
trait OutboxEndpointHandler { this: HttpRouteWithPlatform =>

  import akka.actor.typed.scaladsl.adapter._

  private def getOutboxDeliveryStatus(outboxId: String): Future[Any] = {
    val clusterSharding = ClusterSharding(system.toTyped)
    val outboxEntityRef = clusterSharding.entityRefFor(Outbox.TypeKey, outboxId)
    outboxEntityRef.ask(ref => Outbox.Commands.GetDeliveryStatus(List(), List(), false, ref))
  }

  private def getMsgDeliveryStatus(msgId: String): Future[Any] = {
    val clusterSharding = ClusterSharding(system.toTyped)
    val messageMetaEntityRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
    messageMetaEntityRef.ask(ref => MessageMeta.Commands.GetDeliveryStatus(ref))
  }

  protected val outboxRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        extractRequest { implicit req =>
          extractClientIP { implicit remoteAddress =>
            pathPrefix("agency" / "internal" / "outbox") {
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              pathPrefix(Segment) { outboxId =>
                (get & pathEnd) {
                  complete {
                    getOutboxDeliveryStatus(outboxId).map[ToResponseMarshallable] {
                      case Success(ds: Outbox.Replies.DeliveryStatus) => handleExpectedResponse(ds)
                      case e => handleUnexpectedResponse(e)
                    }
                  }
                }
              }
              pathPrefix("message-meta") {
                pathPrefix(Segment) { messageId =>
                  (get & pathEnd) {
                    complete {
                      getMsgDeliveryStatus(messageId).map[ToResponseMarshallable] {
                        case Success(mds: MessageMeta.Replies.MsgDeliveryStatus) => handleExpectedResponse(mds)
                        case e => handleUnexpectedResponse(e)
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
}
