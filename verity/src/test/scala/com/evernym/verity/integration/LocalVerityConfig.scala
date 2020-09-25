package com.evernym.verity.integration

import java.nio.file.Path

import com.evernym.verity.config.CommonConfig.{LIB_INDY_LEDGER_POOL_NAME, LIB_INDY_LIBRARY_DIR_LOCATION, LIB_INDY_WALLET_TYPE}
import com.typesafe.config.{Config, ConfigFactory}

case class PortProfile(http: Int, artery: Int, akka_management: Int)

object LocalVerityConfig {

  val defaultPorts = PortProfile(9002, 2552, 8552)

  private def messageSerialization = {
    ConfigFactory.parseString(
      """akka.actor.serialize-messages = on
        |akka.actor.allow-java-serialization = off
        |""".stripMargin
    )
  }

  private def useCustomPort(ports: PortProfile) = {
    ConfigFactory.parseString(
      s"""
         |verity.http.port = ${ports.http}
         |akka.remote.artery.canonical.port = ${ports.artery}
         |akka.cluster.seed-nodes = ["akka://verity@localhost:${ports.artery}"]
         |akka.management.http.port = ${ports.akka_management}
      """.stripMargin
    )
  }

  //Use local and in-memory persistence instead of a remote service
  private def useInmemPersistence(tempDir: Path): Config = {
    ConfigFactory.parseString(
      s"""
         |akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
         |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
         |akka.persistence.snapshot-store.local.dir = "${tempDir.resolve("snapshots")}"
         |""".stripMargin
    )
  }


  private def useDefaultWallet(tempDir: Path): Config = {
    ConfigFactory.parseString(
      s"""
         |$LIB_INDY_WALLET_TYPE = "default"
         |$LIB_INDY_LIBRARY_DIR_LOCATION  = "${tempDir.resolve("indy")}"
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

  private def configureLibIndy(taaEnabled: Boolean, taaAutoAccept: Boolean): Config = {
    ConfigFactory.parseString(
      s"""
         |verity.lib-indy {
         |  ledger {
         |    transaction_author_agreement = {
         |      agreements = {
         |        "1.0.0" {
         |          "digest" = "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305"
         |          "mechanism" = "on_file"
         |          "time-of-acceptance" = "2019-11-18"
         |        }
         |      }
         |      enabled = $taaEnabled
         |      auto-accept = $taaAutoAccept
         |    }
         |  }
         |}""".stripMargin
    )
  }

  private def turnOffWarnings(): Config = {
    ConfigFactory.parseString(
      s"""
         |akka.actor.warn-about-java-serializer-usage = off
         |""".stripMargin
    )
  }

  def standard(tempDir: Path, port: PortProfile, taaEnabled: Boolean = true,
               taaAutoAccept: Boolean = true): Config = {
    val parts = Seq(
      useInmemPersistence(tempDir),
      useDefaultWallet(tempDir),
      changePoolName(),
      useCustomPort(port),
      turnOffWarnings(),
      messageSerialization,
      configureLibIndy(taaEnabled, taaAutoAccept),
      ConfigFactory.load()
    )
    val t = parts.fold(ConfigFactory.empty())(_.withFallback(_).resolve())
    t
  }

}