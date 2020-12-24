package com.evernym.verity.actor

import java.util.UUID

import akka.actor.PoisonPill
import akka.testkit.ImplicitSender
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import com.evernym.verity.actor.wallet._


class WalletActorSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually {

  lazy val walletActorEntityId: String = UUID.randomUUID().toString
  lazy val walletActor: agentRegion = agentRegion(walletActorEntityId, walletRegionActor)

  "WalletActor" - {

    "when sent CreateWallet command" - {
      "should respond with WalletCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletCreated.type]
      }
    }

    "when sent CreateWallet command again" - {
      "should respond with WalletAlreadyCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command" -{
      "should respond with NewKeyCreated" in {
        walletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent PoisonPill command" -{
      "should stop the actor" in {
        walletActor ! PoisonPill
        expectNoMessage()
      }
    }

    "when sent CreateWallet command" - {
      "should respond with WalletAlreadyCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command again" -{
      "should respond with NewKeyCreated" in {
        walletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }
  }

}
