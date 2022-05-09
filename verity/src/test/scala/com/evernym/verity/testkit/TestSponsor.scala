package com.evernym.verity.testkit

import akka.actor.ActorSystem
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, SignMsg, SignedMsg}
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.vault.KeyParam

import scala.concurrent.ExecutionContext


class TestSponsor(seed: String,
                  futExecutionContext: ExecutionContext,
                  system: ActorSystem) {
  val sponsorWallet = new TestWallet(futExecutionContext, createWallet = true, system)
  private val sponsorKey: NewKeyCreated = sponsorWallet.executeSync[NewKeyCreated](CreateNewKey(seed=Some(seed)))
  val verKey: VerKeyStr = sponsorKey.verKey

  def sign(nonce: String,
           sponseeId: String,
           sponsorId: String,
           timestamp: String,
           verKey: VerKeyStr): Base64Encoded = {

    val signedMsg = sponsorWallet.executeSync[SignedMsg](
      SignMsg(KeyParam.fromVerKey(verKey), (nonce + timestamp + sponseeId + sponsorId).getBytes())
    )
    Base64Util.getBase64Encoded(signedMsg.msg)
  }
}
