package com.evernym.verity.actor.persistence

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardRegion.{CurrentShardRegionState, ExtractEntityId, ExtractShardId, GetShardRegionState}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.evernym.verity.actor.{ForIdentifier, ShardIdExtractor, ShardUtil}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.duration.FiniteDuration

class PassivationPersistentSpec
  extends TestKit(ActorSystem("test"))
    with BasicSpec
    with ImplicitSender {
  val sharding = ClusterSharding(system)
  val props = Props(new TestActor())
  val extractShardId: ExtractShardId = ShardUtil.forIdentifierShardIdExtractor(ShardIdExtractor(TestExecutionContextProvider.testAppConfig, "test"))
  val extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor
  val clusterShardingSettings = ClusterShardingSettings(system).withPassivateIdleAfter(FiniteDuration(30, TimeUnit.SECONDS))
  val shardingRegion = sharding.start("test", props, clusterShardingSettings, extractEntityId, extractShardId)

  "PassivationPersistentSpec" - {
    "when timeout hits actor should be passivated" in {
      val probe = TestProbe()
      shardingRegion ! ForIdentifier("123", TestMessage("test", probe.ref))
      probe.expectMsg(TestReply("test"))
      shardingRegion ! GetShardRegionState
      val clusterState = receiveOne(FiniteDuration(10, TimeUnit.SECONDS))
      clusterState should not be null
      System.out.print(clusterState.asInstanceOf[CurrentShardRegionState].shards.toString())
    }
  }

}

case class TestMessage(msg: String, replyTo: ActorRef)
case class TestReply(msg: String)

class TestActor extends Actor {
  override def receive = {
    case TestMessage(msg, replyTo) => {
      System.out.println("Received TestMessage")
      replyTo ! TestReply(msg)
    }
  }
}