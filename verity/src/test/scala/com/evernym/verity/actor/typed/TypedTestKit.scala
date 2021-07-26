package com.evernym.verity.actor.typed

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.{EventSourcedBehaviorTestKit, PersistenceTestKit, SnapshotTestKit}
import com.evernym.verity.integration.base.PortProvider
import com.evernym.verity.metrics.{MetricsWriterExtension, TestMetricsBackend}
import com.typesafe.config.{Config, ConfigFactory}


abstract class BehaviourSpecBase
  extends ScalaTestWithActorTestKit(
    ActorTestKit(
      "TestSystem",
      TypedTestKit.config.withFallback(TypedTestKit.clusterConfig)
    )
  ) {

  val testMetricsWriter: TestMetricsBackend = new TestMetricsBackend
  MetricsWriterExtension(system).updateMetricsBackend(testMetricsWriter)
}

abstract class EventSourcedBehaviourSpecBase
  extends BehaviourSpecBase {
  lazy val persTestKit: PersistenceTestKit = PersistenceTestKit(system)
  lazy val snapTestKit: SnapshotTestKit = SnapshotTestKit(system)
}

object TypedTestKit {

  val config: Config = ConfigFactory.empty
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)

  def clusterConfig: Config = {
    val randomPort = PortProvider.generateUnusedPort(2000)

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
          "akka://TestSystem@127.0.0.1:$randomPort",
        ]
        downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
        jmx.multi-mbeans-in-same-jvm = on
      }
    }
    """)
  }
}
