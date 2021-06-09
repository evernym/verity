package com.evernym.verity.protocol.protocols.walletBackup

import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.protocols.walletBackup.legacy.LegacyState

trait BackupState

trait State

object State extends LegacyState {
  // General
  case class Uninitialized() extends BackupState
  case class Initialized() extends BackupState

  // Persister
  case class RecoveryModeRequested(vk: VerKey) extends BackupState
  case class ReadyToPersistBackupRef(vk: VerKey, blobAddress: String) extends BackupState

  // Exporter
  case class BackupInitRequested() extends BackupState
  case class ReadyToExportBackup() extends BackupState
  case class BackupInProgress() extends BackupState

  case class RecoveringBackup() extends BackupState
  case class Recovered() extends BackupState
}



