package com.evernym.verity.actor.persistence

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.cluster.sharding.ShardRegion.{CurrentShardRegionState, ExtractEntityId, ExtractShardId, GetShardRegionState}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.TestKit
import com.evernym.verity.actor.agent.msgrouter.Route
import com.evernym.verity.actor.{ForIdentifier, ShardIdExtractor, ShardUtil}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.duration.FiniteDuration

class PassivationPersistentSpec
  extends TestKit(ActorSystem("test"))
  with BasicSpec {
  val sharding = ClusterSharding(system)
  val props = Route.props(TestExecutionContextProvider.ecp.futureExecutionContext)(TestExecutionContextProvider.testAppConfig)
  val extractShardId: ExtractShardId = ShardUtil.forIdentifierShardIdExtractor(ShardIdExtractor(TestExecutionContextProvider.testAppConfig, "test"))
  val extractEntityId: ExtractEntityId = ShardUtil.forIdentifierEntityIdExtractor
  val clusterShardinSettings = ClusterShardingSettings(system).withPassivateIdleAfter(FiniteDuration(30, TimeUnit.SECONDS))
  val shardingRegion = sharding.start("test", props, clusterShardinSettings, extractEntityId, extractShardId)

  "PassivationPersistentSpec" - {
    "when timeout hits actor should be passivated" in {
      shardingRegion ! ForIdentifier("123", "")
      shardingRegion ! GetShardRegionState
      expectMsgType[CurrentShardRegionState.type]
    }
  }

}
