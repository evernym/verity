package com.evernym.verity.integration.with_basic_sdk.data_retention

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.integration.base.{PortProvider, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.verity_provider.node.local.ServiceParam
import com.evernym.verity.storage_services.StorageAPI
import com.typesafe.config.{Config, ConfigFactory}

import java.net.InetAddress


trait DataRetentionBaseSpec { this: VerityProviderBaseSpec =>

  val DATA_RETENTION_CONFIG: Config

  lazy val appConfig: TestAppConfig = TestAppConfig(Option(DATA_RETENTION_CONFIG), clearValidators = true)

  val ledgerTxnExecutor = new MockLedgerTxnExecutor(futureExecutionContext)

  def buildSvcParam: ServiceParam =
    ServiceParam
      .empty
      .withLedgerTxnExecutor(ledgerTxnExecutor)
      .withStorageApi(StorageAPI.loadFromConfig(appConfig, futureExecutionContext))

  val arteryPort: Int = PortProvider.getFreePort

  implicit lazy val actorSystem: ActorSystem = {
    val parts = Seq(akkaConfig(arteryPort))
    val config = parts.fold(ConfigFactory.empty())(_.withFallback(_).resolve())
    ActorSystem("storage-system", config)
  }

  def akkaConfig(port: Int): Config =
    ConfigFactory.parseString(
      s"""
         |akka.cluster.jmx.multi-mbeans-in-same-jvm = on
         |akka.remote.artery.canonical.hostname = ${InetAddress.getLocalHost.getHostAddress}
         |akka.remote.artery.canonical.port = $port
    """.stripMargin
    )
}
