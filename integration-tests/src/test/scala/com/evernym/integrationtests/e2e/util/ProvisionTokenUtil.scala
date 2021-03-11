package com.evernym.integrationtests.e2e.util

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.ProvisionToken
import com.evernym.verity.sdk.wallet.DefaultWalletConfig
import com.evernym.verity.util.{Base64Util, TimeUtil}
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.did.{Did, DidJSONParameters}
import org.hyperledger.indy.sdk.wallet.Wallet

import java.util.UUID

object ProvisionTokenUtil {

  def genTokenOpt(sponsorSeed: Option[String]): Option[String] = {
    sponsorSeed.map(genToken)
  }

  def genToken(sponsorSeed: String): String = {
    val sponseeId = UUID.randomUUID().toString
    val sponsorId = sponsorSeed
    val nonce = UUID.randomUUID().toString
    val timestamp = TimeUtil.nowDateString
    val bytes = (nonce + timestamp + sponseeId + sponsorId).getBytes()
    val walletConfig = DefaultWalletConfig.build(
      s"sponsor-temp-wallet-$nonce",
      nonce
    )
    Wallet.createWallet(walletConfig.config(), walletConfig.credential()).get()
    val wallet = Wallet.openWallet(walletConfig.config(), walletConfig.credential()).get()
    val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(null, sponsorSeed, null, null)
    val verKey = Did.createAndStoreMyDid(wallet,DIDJson.toJson).get().getVerkey
    val sig = Base64Util.getBase64Encoded(Crypto.cryptoSign(wallet, verKey, bytes).get())


    DefaultMsgCodec.toJson(
      ProvisionToken(
        sponseeId,
        sponsorId,
        nonce,
        timestamp,
        sig,
        verKey
      )
    )
  }

}
