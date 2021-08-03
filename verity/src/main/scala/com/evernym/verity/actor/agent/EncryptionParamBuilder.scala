package com.evernym.verity.actor.agent

import com.evernym.verity.did.{DID, VerKey}
import com.evernym.verity.vault.{EncryptParam, KeyParam}

case class EncryptionParamBuilder(encryptParam: EncryptParam = EncryptParam(Set.empty, None)) {

  def withRecipDID(did: DID): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeyParams =
      Set(KeyParam.fromDID(did))))
  }
  def withRecipVerKey(verKey: VerKey): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(recipKeyParams = Set(KeyParam.fromVerKey(verKey))))
  }
  def withSenderVerKey(verKey: VerKey): EncryptionParamBuilder = {
    copy(encryptParam = encryptParam.copy(senderKeyParam = Option(KeyParam.fromVerKey(verKey))))
  }
}
