package com.evernym.verity.actor.persistence

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.cluster.sharding.ShardRegion.{CurrentShardRegionState, GetShardRegionState}
import akka.cluster.sharding.ClusterSharding
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.actor.testkit.HasBasicActorSystem
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.testkit.BasicSpec

import scala.concurrent.duration.FiniteDuration

class PassivationPersistentSpec
  extends BasicSpec
    with TestKitBase
    with HasBasicActorSystem
    with ImplicitSender
    with ShardUtil {
  val region = createNonPersistentRegion(
    "test",
    Props[TestActor](),
    passivateIdleEntityAfter = Some(FiniteDuration(20, TimeUnit.SECONDS))
  )(system)

  "PassivationPersistentSpec" - {
    "when timeout hits actor should be passivated" in {
      val shardingRegion = ClusterSharding(system).shardRegion("test")
      shardingRegion ! ForIdentifier("123", TestMessage("test"))
      expectMsg(TestReply("test"))
      shardingRegion ! GetShardRegionState
      val clusterState = receiveOne(FiniteDuration(10, TimeUnit.SECONDS))
      clusterState should not be null
      var state: CurrentShardRegionState = clusterState.asInstanceOf[CurrentShardRegionState]
      state.shards.foreach(shard => {
        shard.entityIds.size shouldBe 1
      })
      Thread.sleep(30000)
      awaitCond({
        shardingRegion ! GetShardRegionState
        val clusterStateAfterTimeout = receiveOne(FiniteDuration(10, TimeUnit.SECONDS))
        state = clusterStateAfterTimeout.asInstanceOf[CurrentShardRegionState]
        state.shards.map(_.entityIds.size).sum == 0
      }, Duration(35, TimeUnit.SECONDS), Duration(2, TimeUnit.SECONDS))
    }
  }

}

case class TestMessage(msg: String)
case class TestReply(msg: String)

class TestActor extends Actor {
  override def receive = {
    case TestMessage(msg) =>
      sender() ! TestReply(msg)

  }
}