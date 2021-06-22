package com.evernym.verity.actor.typed

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.{Config, ConfigFactory}

object TypedTestKit {

  val config: Config = ConfigFactory.parseString(
    """
    akka.actor {
      serialization-bindings {
        "com.evernym.verity.actor.typed.Encodable" = jackson-cbor
      }
    }
    """)
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)

  val clusterConfig: Config = ConfigFactory.parseString(
    """
    akka {
      actor {
        provider = "cluster"
      }
      remote.artery {
        canonical {
          hostname = "127.0.0.1"
          port = 2551
        }
      }

      cluster {
        seed-nodes = [
          "akka://TestSystem@127.0.0.1:2551",
        ]
        downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
      }
    }
    """)
}
