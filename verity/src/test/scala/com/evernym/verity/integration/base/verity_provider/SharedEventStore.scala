package com.evernym.verity.integration.base.verity_provider

import akka.actor.{ActorSystem, ExtendedActorSystem}
import com.evernym.verity.integration.base.PortProvider
import com.typesafe.config.{Config, ConfigFactory}

import java.net.InetAddress
import java.nio.file.{Files, Path}

/**
 * this class holds an actor system which is serving the event and snapshot storage
 * this is mainly useful when there is multi node cluster with file based journal (like level db)
 * which does posses locking challenges if all nodes try to use the same storage.
 *
 * NOTE: this shared event store may not be scalable/efficient,
 * so it's usage should be only for testing general scenarios not for any performance test.
 *
 * @param tempDir directory where journal and snapshot will be stored
 */
class SharedEventStore(tempDir: Path) {

  val arteryPort: Int = PortProvider.getFreePort

  val actorSystem: ActorSystem = {
    val parts = Seq(
      sharedEventStoreConfig(),
      otherAkkaConfig(arteryPort)
    )
    val config = parts.fold(ConfigFactory.empty())(_.withFallback(_).resolve())
    ActorSystem("shared-event-store", config)
  }

  //address used by other nodes to point to this system as a journal/snapshot storage
  val address = actorSystem.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  def sharedEventStoreConfig(): Config = {
    val sharedDir = Files.createTempDirectory(tempDir, "shared-").toAbsolutePath
    ConfigFactory.parseString(
      s"""
         |akka.extensions = ["akka.persistence.journal.PersistencePluginProxyExtension"]
         |akka.persistence {
         |  journal.proxy.start-target-journal = on
         |  snapshot-store.proxy.start-target-snapshot-store = on
         |}
         |akka.persistence.journal {
         |  plugin = "akka.persistence.journal.proxy"
         |  proxy.target-journal-plugin = "akka.persistence.journal.leveldb"
         |  leveldb {
         |    dir = "$sharedDir"
         |    native = false
         |  }
         |}
         |akka.persistence.snapshot-store {
         |  plugin = "akka.persistence.snapshot-store.proxy"
         |  proxy.target-snapshot-store-plugin = "akka.persistence.snapshot-store.local"
         |  local = {
         |    dir = "${sharedDir.resolve("snapshots")}"
         |  }
         |}
         |""".stripMargin
    )
  }

  def otherAkkaConfig(port: Int): Config = {
    ConfigFactory.parseString(
      s"""
         |akka.actor.provider = cluster
         |akka.http.server.remote-address-header = on
         |akka.cluster.jmx.multi-mbeans-in-same-jvm = on
         |akka.remote.artery.canonical.hostname = ${InetAddress.getLocalHost.getHostAddress}
         |akka.remote.artery.canonical.port = $port
    """.stripMargin
    )
  }

}