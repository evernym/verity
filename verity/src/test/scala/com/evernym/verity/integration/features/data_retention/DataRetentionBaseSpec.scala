package com.evernym.verity.integration.features.data_retention

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.{ActorSystemVanilla, MockLedgerTxnExecutor}
import com.evernym.verity.integration.base.{PortProvider, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.verity_provider.node.local.ServiceParam
import com.evernym.verity.protocol.engine.MockVDRAdapter
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.{MockIndyLedger, MockLedgerRegistryBuilder, MockVdrTools}
import com.typesafe.config.{Config, ConfigFactory}

import java.net.InetAddress


trait DataRetentionBaseSpec { this: VerityProviderBaseSpec =>

  val DATA_RETENTION_CONFIG: Config

  lazy val appConfig: TestAppConfig = TestAppConfig(Option(DATA_RETENTION_CONFIG), clearValidators = true)
  lazy val vdrTools = new MockVdrTools(
    MockLedgerRegistryBuilder()
      .withLedger(INDY_SOVRIN_NAMESPACE, MockIndyLedger("genesis.txn file path", None)).build()
  )(futureExecutionContext)
  lazy val vdrToolsAdapter = new MockVDRAdapter(vdrTools)(futureExecutionContext)
  lazy val ledgerTxnExecutor = new MockLedgerTxnExecutor(futureExecutionContext, appConfig, vdrToolsAdapter)


  def buildSvcParam: ServiceParam =
    ServiceParam
      .empty
      .withLedgerTxnExecutor(ledgerTxnExecutor)
      .withVdrTools(vdrTools)
      .withStorageApi(StorageAPI.loadFromConfig(appConfig, futureExecutionContext))

  val arteryPort: Int = PortProvider.getFreePort

  implicit lazy val actorSystem: ActorSystem = {
    val systemName = "storage-system"
    val parts = Seq(akkaConfig(systemName, arteryPort))
    val config = parts.fold(ConfigFactory.empty())(_.withFallback(_).resolve())
    ActorSystemVanilla(systemName, config)
  }

  def akkaConfig(systemName: String, port: Int): Config =
    ConfigFactory.parseString(
      s"""
         |akka.cluster.jmx.multi-mbeans-in-same-jvm = on
         |akka.remote.artery.canonical.hostname = ${InetAddress.getLocalHost.getHostAddress}
         |akka.remote.artery.canonical.port = $port
         |akka.cluster.seed-nodes = [
         |  "akka://$systemName@${InetAddress.getLocalHost.getHostAddress}:$port"
         |]
    """.stripMargin
    )
}
