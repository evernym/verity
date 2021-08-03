package com.evernym.verity.protocol.protocols.walletBackup.legacy

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.protocols.walletBackup.BackupState

trait LegacyState {
  case class ReadyToPersistBackup(vk: VerKeyStr, lastWallet: Option[Array[Byte]]) extends BackupState
}
