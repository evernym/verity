package com.evernym.verity.event_bus.adapters.basic

import akka.Done
import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.util.Timeout
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, ShardUtil}
import com.evernym.verity.config.ConfigConstants.NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try


trait BaseEventAdapter
  extends ShardUtil {

  implicit val timeout: Timeout = Timeout(25.seconds)

  protected def sendToTopicActor(topicName: String, msg: ActorMessage)(implicit system: ActorSystem): Future[Done] = {
    val region = getTopicShardedRegion(TOPIC_ACTOR_NAME)
    region.ask(ForIdentifier(topicName, msg)).mapTo[Done]
  }

  //TODO: will this work?
  private def getTopicShardedRegion(topicName: String)(implicit system: ActorSystem): ActorRef = {
    Try(ClusterSharding.get(system).shardRegion(TOPIC_ACTOR_NAME))
      .getOrElse{
        createNonPersistentRegion(
          TOPIC_ACTOR_NAME,
          buildProp(BasicTopic.props(topicName)),
          passivateIdleEntityAfter = Option(
            passivateDuration(NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS, 600.seconds)
          )
        )
        getTopicShardedRegion(topicName)
      }
  }

  val TOPIC_ACTOR_NAME = "Topic"
}
