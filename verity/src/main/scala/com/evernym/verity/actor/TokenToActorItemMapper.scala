package com.evernym.verity.actor

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.persistence.{BasePersistentActor, HasActorResponseTimeout}
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.util.TokenProvider

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.protocol.engine.MsgId

/**
 * this actor is used on inviter side
 *
 * for consumer app (connect.me) to be able to get invitation detail from the invitation sms
 * there was a need to map a given token (small random string) to point to the agent pairwise actor
 * which contains/sent the invitation, so that, it can be queried to respond with full invitation detail
 * for the given token.
 * this actor stores that mapping of token to agent pairwise actor
 * @param system
 * @param appConfig
 */

class TokenToActorItemMapperProvider(system: ActorSystem, val appConfig: AppConfig)
    extends HasActorResponseTimeout
    with HasAppConfig {

  lazy val tokenToActorItemMapperRegion: ActorRef =
    ClusterSharding(system).shardRegion(TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME)

  def createToken(regionTypeName: String, entityId: String, uid: MsgId):
  Future[Either[HandledErrorException, String]] = {
    val token = TokenProvider.getNewToken
    val fut = tokenToActorItemMapperRegion ? ForToken(token, AddDetail(regionTypeName, entityId, uid))
    fut.map { _ =>
      Right(token)
    }
  }
}

object TokenToActorItemMapper {
  def getToken: String = TokenProvider.getNewToken
  def props(implicit appConfig: AppConfig): Props = Props(new TokenToActorItemMapper(appConfig))
}

//cmds
case class AddDetail(regionTypeName: String, actorEntityId: String, uid: MsgId) extends ActorMessageClass
case object GetDetail extends ActorMessageObject

//msgs
case class ActorItemDetail(actorEntityId: String, uid: MsgId, regionTypeName: String) extends ActorMessageClass


class TokenToActorItemMapper(val appConfig: AppConfig)
  extends BasePersistentActor
  with ShardRegionFromActorContext {

  var actorItemDetail: Option[ActorItemDetail] = None

  override lazy val persistenceEncryptionKey: String =
    appConfig.getConfigStringReq(CommonConfig.SECRET_TOKEN_TO_ACTOR_ITEM_MAPPER)

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

}