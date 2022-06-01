package com.evernym.verity.actor.typed

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.{EventSourcedBehaviorTestKit, PersistenceTestKit, SnapshotTestKit}
import com.evernym.verity.integration.base.PortProvider
import com.evernym.verity.observability.metrics.{MetricsWriterExtension, TestMetricsBackend}
import com.typesafe.config.{Config, ConfigFactory}


abstract class BehaviourSpecBase
  extends ScalaTestWithActorTestKit(
    ActorTestKit(
      "verity",
      TypedTestKit.clusterConfig
        .withFallback(TypedTestKit.serializationConfig)
        .withFallback(TypedTestKit.testKitConfig)
    )
  ) {

  val testMetricsBackend: TestMetricsBackend = new TestMetricsBackend
  MetricsWriterExtension(system).updateMetricsBackend(testMetricsBackend)
}

abstract class EventSourcedBehaviourSpecBase
  extends BehaviourSpecBase {
  lazy val persTestKit: PersistenceTestKit = PersistenceTestKit(system)
  lazy val snapTestKit: SnapshotTestKit = SnapshotTestKit(system)
}

object TypedTestKit {

  val testKitConfig: Config = ConfigFactory.empty
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)

  def clusterConfig: Config = {
    val randomPort = PortProvider.getFreePort

    ConfigFactory.parseString(
      s"""
    akka {
      actor {
        provider = "cluster"
      }
      remote.artery {
        canonical {
          hostname = "127.0.0.1"
          port = $randomPort
        }
      }

      cluster {
        seed-nodes = [
          "akka://verity@127.0.0.1:$randomPort",
        ]
        downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
        jmx.multi-mbeans-in-same-jvm = on
      }
    }
    """)
  }

  val serializationConfig: Config = ConfigFactory.parseString(
    """
      |akka.actor {
      |  serialize-messages = on    # to make sure commands/reply messages are tested for remote serialization
      |  allow-java-serialization = off
      |
      |  serializers {
      |    protoser = "com.evernym.verity.actor.serializers.ProtoBufSerializer"
      |  }
      |
      |  serialization-bindings {
      |    "com.evernym.verity.actor.PersistentMsg" = protoser
      |    "com.evernym.verity.actor.ActorMessage" = kryo-akka
      |  }
      |}
      |
      |akka.persistence.testkit {
      |     events.serialize = on
      |     snapshots.serialize = on
      |}
      |""".stripMargin)

}
