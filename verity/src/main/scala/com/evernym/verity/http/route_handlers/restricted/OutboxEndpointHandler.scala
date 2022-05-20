package com.evernym.verity.http.route_handlers.restricted

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.StatusReply.Success
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.Outbox

import scala.concurrent.Future

// todo: should be switched to OutboxService usage after Outbox work finalization
trait OutboxEndpointHandler extends BaseRequestHandler {
  this: PlatformWithExecutor =>

  import akka.actor.typed.scaladsl.adapter._

  private def getOutboxDeliveryStatus(outboxId: String): Future[Any] = {
    val clusterSharding = ClusterSharding(platform.actorSystem.toTyped)
    val outboxEntityRef = clusterSharding.entityRefFor(Outbox.TypeKey, outboxId)
    outboxEntityRef.ask(ref => Outbox.Commands.GetDeliveryStatus(List(), List(), false, ref))
  }

  private def getMsgDeliveryStatus(msgId: String): Future[Any] = {
    val clusterSharding = ClusterSharding(platform.actorSystem.toTyped)
    val messageMetaEntityRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
    messageMetaEntityRef.ask(ref => MessageMeta.Commands.GetDeliveryStatus(ref))
  }

  protected val outboxRoute: Route =
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix("agency" / "internal" / "outbox") {
        pathPrefix(Segment) { outboxId =>
          (get & pathEnd) {
            complete {
              getOutboxDeliveryStatus(outboxId).map {
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
                getMsgDeliveryStatus(messageId).map {
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
