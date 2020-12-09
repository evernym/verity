package com.evernym.verity.actor

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import com.evernym.verity.actor.wallet.{DeleteWallet, WalletActor, WalletDeleted}
class WalletActorSpec extends TestKitBase
  with ProvidesMockPlatform
  with BasicSpec
  with ImplicitSender
  with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()

  "WalletActor" - {

    "creating WalletActor" - {
      "should create and open wallet" in {
        val walletActor =
          agentActorContext.system.actorOf(Props(new WalletActor(agentActorContext.appConfig)), name = "WALLET_ACTOR")

        walletActor ! DeleteWallet()
        expectMsgType[WalletDeleted]

      }
    }
  }


}
