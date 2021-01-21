package com.evernym.verity.actor.agent

import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.{EncryptParam, KeyParam}

import scala.util.Left

object EncryptionParamBuilder {
  def default(vkc: WalletVerKeyCacheHelper): EncryptionParamBuilder = new EncryptionParamBuilder (vkc)
}

case class EncryptionParamBuilder(walletVerKeyCacheHelper: WalletVerKeyCacheHelper,
                             encryptParam: EncryptParam = EncryptParam(Set.empty, None)) {

  //TODO: this uses cache (which internally uses blocking wallet api call if item not found in cache),
  // should try to refactor it
  def withRecipDID(did: DID): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeyParams =
      Set(KeyParam(Left(walletVerKeyCacheHelper.getVerKeyReqViaCache(did))))))
  }
  def withRecipVerKey(verKey: VerKey): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeyParams = Set(KeyParam(Left(verKey)))))
  }
  def withSenderVerKey(verKey: VerKey): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(senderKeyParam = Option(KeyParam(Left(verKey)))))
  }
}
