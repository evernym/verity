package com.evernym.verity.actor

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.cluster.sharding.ClusterSharding
import akka.util.Timeout
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status._
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.util.TokenProvider

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.config.CommonConfig.TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.constants.Constants.DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.util.Util.buildDuration

import scala.concurrent.duration.FiniteDuration

/**
 * this actor is used on inviter side
 *
 * for consumer app (connect.me) to be able to get invitation detail from the invitation sms
 * there was a need to map a received token (small random string) in SMS,
 * to point to the agent pairwise actor on inviter (enterprise side)
 * which contains the invitation details, so that it can be queried to get full invitation detail.

 * this actor stores that mapping of "token" to "agent pairwise actor"
 */

object TokenToActorItemMapperProvider {

  def createToken(regionTypeName: String, entityId: String, uid: MsgId)(appConfig: AppConfig, system: ActorSystem):
  Future[Either[HandledErrorException, String]] = {
    val duration: FiniteDuration =
      buildDuration(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
    implicit lazy val responseTimeout: Timeout = Timeout(duration)
    val tokenToActorItemMapperRegion = ClusterSharding(system).shardRegion(TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME)
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
case class AddDetail(regionTypeName: String, actorEntityId: String, uid: MsgId) extends ActorMessage
case object GetDetail extends ActorMessage

//msgs
case class ActorItemDetail(actorEntityId: String, uid: MsgId, regionTypeName: String) extends ActorMessage


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