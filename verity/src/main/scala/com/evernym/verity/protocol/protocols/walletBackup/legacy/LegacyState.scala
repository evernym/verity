package com.evernym.verity.protocol.protocols.walletBackup.legacy

import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.protocols.walletBackup.BackupState

trait LegacyState {
  case class ReadyToPersistBackup(vk: VerKey, lastWallet: Option[Array[Byte]]) extends BackupState
}
