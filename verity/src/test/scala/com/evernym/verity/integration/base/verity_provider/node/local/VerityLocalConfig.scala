package com.evernym.verity.integration.base.verity_provider.node.local

import akka.persistence.journal.leveldb.SharedLeveldbJournal
import com.evernym.verity.config.ConfigConstants.{LIB_INDY_LEDGER_POOL_NAME, LIB_VDRTOOLS_LIBRARY_DIR_LOCATION, LIB_VDRTOOLS_WALLET_TYPE}
import com.evernym.verity.integration.base.verity_provider.{PortProfile, SharedEventStore}
import com.typesafe.config.{Config, ConfigFactory}

import java.net.InetAddress
import java.nio.file.Path

object VerityLocalConfig {

  private def messageSerialization(): Config = {
    ConfigFactory.parseString(
      //TODO: once we fix root cause behind serialization issue, then we should turn this on again.
      """akka.actor.serialize-messages = off
        |akka.actor.allow-java-serialization = off
        |""".stripMargin
    )
  }

  /**
   * @param ports port profile of the this/current node of the cluster
   * @param otherNodeArteryPorts other node's (of the same cluster) artery port
   *                             to be used to populate seed-nodes
   * @return
   */
  private def useCustomPort(ports: PortProfile, otherNodeArteryPorts: Seq[Int]): Config = {
    ConfigFactory.parseString(
      s"""
         |verity.http.port = ${ports.http}
         |verity.endpoint.port = ${ports.http}
         |akka.remote.artery.canonical.hostname = ${InetAddress.getLocalHost.getHostAddress}
         |akka.remote.artery.canonical.port = ${ports.artery}
         |akka.management.http.port = ${ports.akkaManagement}
      """.stripMargin +
        "akka.cluster.seed-nodes = [" + "\n" +
          (ports.artery +: otherNodeArteryPorts).sorted.map { port =>
            s"""  \"akka://verity@${InetAddress.getLocalHost.getHostAddress}:$port\""""
          }.mkString(",\n") + "\n" +
        "]".stripMargin
    )
  }

  //Use local/shared leveldb persistence
  private def useLevelDBPersistence(tempDir: Path, sharedEventStore: Option[SharedEventStore]=None): Config = {
    sharedEventStore match {
      case Some(ses) =>
        //TODO: we have to turn on java serialization for internal persistent messages to be sent to remote
        // shared event store (may come back to this to see if it can be fixed in different way)
        ConfigFactory.parseString(s"""
         |akka.actor.allow-java-serialization = on
         |akka.extensions = ["akka.persistence.Persistence"]
         |akka.persistence.journal.auto-start-journals = [""]
         |akka.persistence.journal.proxy.target-journal-address = "${ses.address}"
         |akka.persistence.snapshot-store.proxy.target-snapshot-store-address = "${ses.address}"
         |akka.persistence.journal {
         |  plugin = "akka.persistence.journal.proxy"
         |  proxy.target-journal-plugin = "akka.persistence.journal.leveldb"
         |}
         |akka.persistence.snapshot-store {
         |  plugin = "akka.persistence.snapshot-store.proxy"
         |  proxy.target-snapshot-store-plugin = "akka.persistence.snapshot-store.local"
         |}""".stripMargin).withFallback(SharedLeveldbJournal.configToEnableJavaSerializationForTest)
      case None      =>
        ConfigFactory.parseString(s"""
         |akka.persistence.journal {
         |  plugin = "akka.persistence.journal.leveldb"
         |  leveldb {
         |    dir = "$tempDir/journal"
         |    native = false
         |  }
         |}
         |akka.persistence.snapshot-store {
         |  plugin = "akka.persistence.snapshot-store.local"
         |  local = {
         |    dir = "${tempDir.resolve("snapshots")}"
         |  }
         |}
         |""".stripMargin
        )
    }
  }

  private def useDefaultWallet(tempDir: Path): Config = {
    ConfigFactory.parseString(
      s"""
         |$LIB_VDRTOOLS_WALLET_TYPE = "default"
         |$LIB_VDRTOOLS_LIBRARY_DIR_LOCATION  = "${tempDir.resolve("indy")}"
         |""".stripMargin
    )
  }

  private def changePoolName(): Config = {
    ConfigFactory.parseString(
      s"""
         |$LIB_INDY_LEDGER_POOL_NAME = "verity-pool"
         |""".stripMargin
    )
  }

  private def akkaConfig() = {
    ConfigFactory.parseString(
      s"""
         |akka.actor.provider = cluster
         |akka.http.server.remote-address-header = on
         |akka.cluster.jmx.multi-mbeans-in-same-jvm = on
         |""".stripMargin
    )
  }

  private def configureLibIndy(taaEnabled: Boolean, taaAutoAccept: Boolean): Config = {
    ConfigFactory.parseString(
      s"""
         |verity.lib-vdrtools {
         |  ledger {
         |    indy {
         |      transaction_author_agreement = {
         |        agreements = {
         |          "1.0.0" {
         |            "digest" = "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305"
         |            "mechanism" = "on_file"
         |            "time-of-acceptance" = "2019-11-18"
         |          }
         |        }
         |        enabled = $taaEnabled
         |        auto-accept = $taaAutoAccept
         |      }
         |    }
         |  }
         |}""".stripMargin
    )
  }

  private def prometheusServer(port: Int): Config = {
    ConfigFactory.parseString(
      s"""
         |kamon.prometheus.embedded-server.port = $port
         |""".stripMargin
    )
  }

  private def turnOffWarnings(): Config = {
    ConfigFactory.parseString(
      s"""
         |akka.actor.warn-about-java-serializer-usage = off
         |""".stripMargin
    )
  }

  private def coordinatedShutdownConfig(): Config = {
    ConfigFactory.parseString(
      """
        |akka.coordinated-shutdown.phases.before-service-unbind.timeout = 5 s
        |
        |verity.draining {
        |  //maximum check attempts to ensure draining state is communicated
        |  max-check-count = 0
        |
        |  //how frequently to check if draining state is communicated/known by the LB
        |  check-interval = 1 s
        |
        |  //how much time to wait (to serve existing received requests)
        |  // before letting service-unbind phase to continue
        |  wait-before-service-unbind = 0 s
        |}
        |""".stripMargin
    )
  }

  private def identityUrlShortener(): Config = {
    ConfigFactory.parseString(
      s"""
         |verity.services.url-shortener-service.selected = "com.evernym.verity.urlshortener.IdentityUrlShortener"
         |""".stripMargin
    )
  }

  def customOnly(tempDir: Path,
                 port: PortProfile,
                 otherNodeArteryPorts: Seq[Int] = Seq.empty,
                 taaEnabled: Boolean = true,
                 taaAutoAccept: Boolean = true,
                 sharedEventStore: Option[SharedEventStore]=None): Config = {
    val parts = Seq(
      testMetricsBackend(),
      useLevelDBPersistence(tempDir, sharedEventStore),
      useDefaultWallet(tempDir),
      useCustomPort(port, otherNodeArteryPorts),
      configureLibIndy(taaEnabled, taaAutoAccept),
      identityUrlShortener(),
      prometheusServer(port.prometheusPort),

      akkaConfig(),
      coordinatedShutdownConfig(),
      changePoolName(),
      turnOffWarnings(),
      messageSerialization()
    )

    parts.fold(ConfigFactory.empty())(_.withFallback(_).resolve())
  }

  def testMetricsBackend() : Config = {
    ConfigFactory.parseString(
      """
        |verity.metrics.backend = "com.evernym.verity.observability.metrics.TestMetricsBackend"
        |""".stripMargin)
  }

  def standard(tempDir: Path,
               port: PortProfile,
               otherNodeArteryPorts: Seq[Int] = Seq.empty,
               taaEnabled: Boolean = true,
               taaAutoAccept: Boolean = true,
               sharedEventStore: Option[SharedEventStore]=None): Config = {
    val customConfig = customOnly(
      tempDir,
      port,
      otherNodeArteryPorts,
      taaEnabled,
      taaAutoAccept,
      sharedEventStore
    )
    customConfig.withFallback(ConfigFactory.load())
  }

}
