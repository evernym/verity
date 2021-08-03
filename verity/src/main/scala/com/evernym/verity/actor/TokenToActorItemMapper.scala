package com.evernym.verity.actor

import akka.actor.Props
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.util.TokenProvider
import com.evernym.verity.protocol.engine.MsgId

import scala.concurrent.ExecutionContext

/**
 * this actor is used on inviter side
 *
 * for consumer app (connect.me) to be able to get invitation detail from the invitation sms
 * there was a need to map a received token (small random string) in SMS,
 * to point to the agent pairwise actor on inviter (enterprise side)
 * which contains the invitation details, so that it can be queried to get full invitation detail.

 * this actor stores that mapping of "token" to "agent pairwise actor"
 */

object TokenToActorItemMapper {
  def getToken: String = TokenProvider.getNewToken
  def props(executionContext: ExecutionContext)(implicit appConfig: AppConfig): Props = Props(new TokenToActorItemMapper(appConfig, executionContext))
}

//cmds
case class AddDetail(regionTypeName: String, actorEntityId: String, uid: MsgId) extends ActorMessage
case object GetDetail extends ActorMessage

//msgs
case class ActorItemDetail(actorEntityId: String, uid: MsgId, regionTypeName: String) extends ActorMessage


class TokenToActorItemMapper(val appConfig: AppConfig, executionContext: ExecutionContext)
  extends BasePersistentActor
  with ShardRegionFromActorContext {

  var actorItemDetail: Option[ActorItemDetail] = None

  override lazy val persistenceEncryptionKey: String =
    appConfig.getStringReq(ConfigConstants.SECRET_TOKEN_TO_ACTOR_ITEM_MAPPER)

  val receiveEvent: Receive = {
    case e: TokenToActorItemMappingAdded =>
      val regionTypeName = Evt.getOptionFromValue(e.regionTypeName).getOrElse(userAgentPairwiseRegionName)
      actorItemDetail = Option(ActorItemDetail(e.actorEntityId, e.uid, regionTypeName))
  }

  lazy val receiveCmd: Receive = {
    case _: AddDetail if actorItemDetail.isDefined =>
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode)

    case c: AddDetail => writeApplyAndSendItBack(TokenToActorItemMappingAdded(c.actorEntityId, c.uid, c.regionTypeName))

    case GetDetail => sender ! actorItemDetail
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}