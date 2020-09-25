package com.evernym.verity.actor.agent

import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.{EncryptParam, KeyInfo}

import scala.util.Left

object EncryptionParamBuilder {
  def default(vkc: WalletVerKeyCacheHelper): EncryptionParamBuilder = new EncryptionParamBuilder (vkc)
}

case class EncryptionParamBuilder(walletVerKeyCacheHelper: WalletVerKeyCacheHelper,
                             encryptParam: EncryptParam = EncryptParam(Set.empty, None)) {

  def withRecipDID(did: DID): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeys = Set(KeyInfo(Left(walletVerKeyCacheHelper.getVerKeyReqViaCache(did))))))
  }
  def withRecipVerKey(verKey: VerKey): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeys = Set(KeyInfo(Left(verKey)))))
  }
  def withSenderVerKey(verKey: VerKey): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(senderKey = Option(KeyInfo(Left(verKey)))))
  }
}
