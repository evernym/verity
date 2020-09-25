package com.evernym.verity.protocol.protocols.walletBackup

import com.evernym.verity.protocol.engine.VerKey

sealed trait BackupState

sealed trait State
object State {
  // General
  case class Uninitialized() extends BackupState
  case class Initialized() extends BackupState

  // Persister
  case class RecoveryModeRequested(vk: VerKey) extends BackupState
  case class ReadyToPersistBackup(vk: VerKey, lastWallet: Option[Array[Byte]]) extends BackupState

  // Exporter
  case class BackupInitRequested() extends BackupState
  case class ReadyToExportBackup() extends BackupState
  case class BackupInProgress() extends BackupState

  case class RecoveringBackup() extends BackupState
  case class Recovered() extends BackupState
}



