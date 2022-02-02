package com.evernym.verity.actor.entityidentifier

import akka.actor.ActorPath
import com.evernym.verity.actor.base.EntityIdentifier.{EntityIdentity, parsePath}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EntityIdentifierSpec extends AnyFreeSpec with Matchers {


  "EntityIdentifier object" - {
    "for NON persistent actor" - {
      "should parse path correctly" in {
        //it is ok if you have to change these asserts because these should be NON persisted actors
        parsePath(ActorPath.fromString("akka://verity/system/sharding/WalletActor/59/30629617-1d2a-43b1-afb3-dfe5389d7e01")) shouldBe
          EntityIdentity("30629617-1d2a-43b1-afb3-dfe5389d7e01", "WalletActor", Some(59), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/MsgTracer/8/ac68a6c6-c485-4a09-8e7f-d13d33b6f1e0")) shouldBe
          EntityIdentity("ac68a6c6-c485-4a09-8e7f-d13d33b6f1e0", "MsgTracer", Some(8), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/MsgProgressTracker/81/global")) shouldBe
          EntityIdentity("global", "MsgProgressTracker", Some(81), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/user/node-singleton")) shouldBe
          EntityIdentity("node-singleton", "Default", None, true, false, false)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton")) shouldBe
          EntityIdentity("singleton", "cluster-singleton-mngrChild", None, false, false, false)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/watcher-manager")) shouldBe
          EntityIdentity("watcher-manager", "cluster-singleton-mngr", None, false, false, true)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/watcher-manager/user-agent-pairwise-actor-watcher")) shouldBe
          EntityIdentity("user-agent-pairwise-actor-watcher", "watcher-managerChild", None, false, false, true)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/route-maintenance-helper")) shouldBe
          EntityIdentity("route-maintenance-helper", "cluster-singleton-mngr", None, false, false, true)
      }
    }

    "for persistent actor" - {
      "should parse path correctly" in {
        //**NOTE**:
        // we should NEVER change any of the asserts/checks in this block of test code without
        // understanding its impact around backward compatibility

        //agent actors
        parsePath(ActorPath.fromString("akka://verity/system/sharding/AgencyAgent/59/30629617-1d2a-43b1-afb3-dfe5389d7e01")) shouldBe
          EntityIdentity("30629617-1d2a-43b1-afb3-dfe5389d7e01", "AgencyAgent", Some(59), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/AgencyAgent/53/d37d361f-8986-4108-bf2d-dad9dfa0bdc3/supervised")) shouldBe
          EntityIdentity("d37d361f-8986-4108-bf2d-dad9dfa0bdc3", "AgencyAgent", Some(53), false, true, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/AgencyAgent/59/30629617-1d2a-43b1-afb3-dfe5389d7e01/supervised/amp-4f1cb237-8355-4ccf-9907-ce37bf6c6c81")) shouldBe
          EntityIdentity("amp-4f1cb237-8355-4ccf-9907-ce37bf6c6c81", "AgencyAgentChild", Some(59), false, true, false)

        parsePath(ActorPath.fromString("akka://verity/system/sharding/AgencyAgentPairwise/81/bb1ca030-fa01-4b56-85d4-3b3e32edd8d6")) shouldBe
          EntityIdentity("bb1ca030-fa01-4b56-85d4-3b3e32edd8d6", "AgencyAgentPairwise", Some(81), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/AgencyAgentPairwise/83/cb1ca030-fa01-4b56-85d4-3b3e32edd8d6/supervised")) shouldBe
          EntityIdentity("cb1ca030-fa01-4b56-85d4-3b3e32edd8d6", "AgencyAgentPairwise", Some(83), false, true, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/AgencyAgentPairwise/59/d0629617-1d2a-43b1-afb3-dfe5389d7e01/supervised/amp-5f1cb237-8355-4ccf-9907-ce37bf6c6c81")) shouldBe
          EntityIdentity("amp-5f1cb237-8355-4ccf-9907-ce37bf6c6c81", "AgencyAgentPairwiseChild", Some(59), false, true, false)

        parsePath(ActorPath.fromString("akka://verity/system/sharding/UserAgent/58/b06c8ec9-368b-4fa5-ab37-b6fdd7caffae")) shouldBe
          EntityIdentity("b06c8ec9-368b-4fa5-ab37-b6fdd7caffae", "UserAgent", Some(58), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/UserAgent/48/87d4b14a-df17-4936-b559-d490b5ec4846/supervised")) shouldBe
          EntityIdentity("87d4b14a-df17-4936-b559-d490b5ec4846", "UserAgent", Some(48), false, true, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/UserAgent/54/a06c8ec9-368b-4fa5-ab37-b6fdd7caffae/supervised/amp-f0882cf9-022f-4bb7-ab64-d31b9c3b0f5e")) shouldBe
          EntityIdentity("amp-f0882cf9-022f-4bb7-ab64-d31b9c3b0f5e", "UserAgentChild", Some(54), false, true, false)

        parsePath(ActorPath.fromString("akka://verity/system/sharding/UserAgentPairwise/63/1754020b-edfb-49f5-a533-3cb7f48661d7")) shouldBe
          EntityIdentity("1754020b-edfb-49f5-a533-3cb7f48661d7", "UserAgentPairwise", Some(63), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/UserAgentPairwise/47/97d4b14a-df17-4936-b559-d490b5ec4846/supervised")) shouldBe
          EntityIdentity("97d4b14a-df17-4936-b559-d490b5ec4846", "UserAgentPairwise", Some(47), false, true, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/UserAgentPairwise/47/97d4b14a-df17-4936-b559-d490b5ec4846/supervised/amp-cf9cd36b-6603-4c01-bc6f-9b761a93fd4c")) shouldBe
          EntityIdentity("amp-cf9cd36b-6603-4c01-bc6f-9b761a93fd4c", "UserAgentPairwiseChild", Some(47), false, true, false)

        //routing actors
        parsePath(ActorPath.fromString("akka://verity/system/sharding/RoutingAgent/34/v1-fe9fc289-c3ff-3af1-82b6-d3bead98a923")) shouldBe
          EntityIdentity("v1-fe9fc289-c3ff-3af1-82b6-d3bead98a923", "RoutingAgent", Some(34), false, false, false)

        //protocol sharded actors
        parsePath(ActorPath.fromString("akka://verity/system/sharding/agent-provisioning-0.5-protocol/42/7ea2e2aa8d9197784b7a466bb7b5bb09")) shouldBe
          EntityIdentity("7ea2e2aa8d9197784b7a466bb7b5bb09", "agent-provisioning-0.5-protocol", Some(42), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/trust_ping-1.0-protocol/78/524181e89e216fb16827160be505531b")) shouldBe
          EntityIdentity("524181e89e216fb16827160be505531b", "trust_ping-1.0-protocol", Some(78), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/relationship-1.0-protocol/53/10380814bf437c803f11b9b801161ddd")) shouldBe
          EntityIdentity("10380814bf437c803f11b9b801161ddd", "relationship-1.0-protocol", Some(53), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/issue-credential-1.0-protocol/39/be1f1a9b53f8a56cc3f8da2ab7c5e64a")) shouldBe
          EntityIdentity("be1f1a9b53f8a56cc3f8da2ab7c5e64a", "issue-credential-1.0-protocol", Some(39), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/dead-drop%5B0.1.0%5D-payload/96/5a01a4801a6efb4ba18afcb5b0bae771-39")) shouldBe
          EntityIdentity("5a01a4801a6efb4ba18afcb5b0bae771-39", "dead-drop%5B0.1.0%5D-payload", Some(96), false, false, false)

        //other sharded actors
        parsePath(ActorPath.fromString("akka://verity/system/sharding/ResourceUsageTracker/66/127.0.1.1")) shouldBe
          EntityIdentity("127.0.1.1", "ResourceUsageTracker", Some(66), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/ResourceUsageTracker/81/global")) shouldBe
          EntityIdentity("global", "ResourceUsageTracker", Some(81), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/ResourceUsageTracker/5/127.0.0.1")) shouldBe
          EntityIdentity("127.0.0.1", "ResourceUsageTracker", Some(5), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/ResourceUsageTracker/63/3V9qmfUMnoMRsKanbXhJgumtcHkFUQBF9MuYpJnjFuSY")) shouldBe
          EntityIdentity("3V9qmfUMnoMRsKanbXhJgumtcHkFUQBF9MuYpJnjFuSY", "ResourceUsageTracker", Some(63), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/ActivityTracker/34/ThjpwgAgSDSUDwZhC7nNA7")) shouldBe
          EntityIdentity("ThjpwgAgSDSUDwZhC7nNA7", "ActivityTracker", Some(34), false, false, false)
        parsePath(ActorPath.fromString("akka://verity/system/sharding/UrlMapper/42/a2c88f71")) shouldBe
          EntityIdentity("a2c88f71", "UrlMapper", Some(42), false, false, false)

        //cluster singleton child actors
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/key-value-mapper")) shouldBe
          EntityIdentity("key-value-mapper", "cluster-singleton-mngr", None, false, false, true)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/user-blocking-status-mngr")) shouldBe
          EntityIdentity("user-blocking-status-mngr", "cluster-singleton-mngr", None, false, false, true)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/user-warning-status-mngr")) shouldBe
          EntityIdentity("user-warning-status-mngr", "cluster-singleton-mngr", None, false, false, true)
        parsePath(ActorPath.fromString("akka://verity/user/cluster-singleton-mngr/singleton/actor-state-cleanup-manager")) shouldBe
          EntityIdentity("actor-state-cleanup-manager", "cluster-singleton-mngr", None, false, false, true)
      }
    }
  }

}
