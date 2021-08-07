package com.evernym.verity.actor.agent

import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.vault.{EncryptParam, KeyParam}

case class EncryptionParamBuilder(encryptParam: EncryptParam = EncryptParam(Set.empty, None)) {

  def withRecipDID(did: DidStr): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeyParams =
      Set(KeyParam.fromDID(did))))
  }
  def withRecipVerKey(verKey: VerKeyStr): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeyParams = Set(KeyParam.fromVerKey(verKey))))
  }
  def withSenderVerKey(verKey: VerKeyStr): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(senderKeyParam = Option(KeyParam.fromVerKey(verKey))))
  }
}
