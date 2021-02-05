package com.evernym.verity.actor.wallet

import akka.testkit.{EventFilter, ImplicitSender}
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.testkit.{ActorSpec, WithAdditionalLogs}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import java.util.UUID
import scala.concurrent.duration._
import scala.language.postfixOps

class WalletActorPassivationSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually
    with WithAdditionalLogs {

  lazy val walletActorEntityId: String = UUID.randomUUID().toString
  lazy val walletActor: agentRegion = agentRegion(walletActorEntityId, walletRegionActor)

  val PASSIVATE_TIMEOUT_IN_SECONDS = 2

  "Wallet Actor" - {
    "when sent CreateWallet command" - {
      "should respond with WalletCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletCreated.type]
      }
    }

    "when waited for more than passivate time" - {
      "should be stopped" in {
        val prevWalletActorRef = lastSender
        EventFilter.debug(pattern = ".*in post stop", occurrences = 1) intercept {
          Thread.sleep((PASSIVATE_TIMEOUT_IN_SECONDS * 1000) + 2000)
        }

        walletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated](5.seconds)
        lastSender should not be prevWalletActorRef //this also indirectly proves actor got restarted
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      """verity.non-persistent-actor.base.WalletActor.passivate-time-in-seconds = 2
         akka.loglevel = DEBUG
        """.stripMargin
    }
  }
}
