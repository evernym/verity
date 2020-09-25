package com.evernym.verity.protocol.protocols.walletBackup

import com.evernym.verity.Base64Encoded
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Constants.MFV_0_1_0
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.msg.PersistenceFailure

object WalletBackupMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER

  val name: MsgFamilyName = "wallet-backup"

  val version: MsgFamilyVersion = MFV_0_1_0

  val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "WALLET_INIT_BACKUP"                    -> classOf[BackupInit],
    "WALLET_BACKUP"                         -> classOf[Backup],
    "WALLET_BACKUP_RESTORE"                 -> classOf[Restore],
    "WALLET_BACKUP_READY"                   -> classOf[BackupReady],
    "WALLET_BACKUP_FAILURE"                 -> classOf[BackupFailure],
    "WALLET_BACKUP_ACK"                     -> classOf[BackupAck],
    "WALLET_BACKUP_RESTORED"                -> classOf[Restored],
  )

  override val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map (
    "Init"                            -> classOf[Init],
    "INIT_BACKUP"                     -> classOf[InitBackup],
    "EXPORT_BACKUP"                   -> classOf[ExportBackup],
    "RECOVER_BACKUP"                  -> classOf[RecoverBackup],
    "RECOVERY_KEY_REGISTERED"         -> classOf[RecoveryKeyRegistered],
    "FAILED_TO_REGISTER_RECOVERY_KEY" -> classOf[FailedToRegisterRecoveryKey],
    "RESTORE_BACKUP"                  -> classOf[RestoreBackup]
  )

  override val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[ProvideRecoveryDetails] -> "ProvideRecoveryDetails",
    classOf[Restored]               -> "WALLET_BACKUP_RESTORED"
  )


  /**
    * Messages used in this protocol for wallet backup
    * Types of messages are from the perspective of the 'sender' of the message
    */
  sealed trait BackupMsg                                extends MsgBase
  case class BackupInit(params: BackupInitParams)       extends BackupMsg
  case class BackupReady()                              extends BackupMsg
  //TODO: RTM - when version upgrades are available, 0.1 should be Array[Byte] and 0.2 should be base65 encoded string
  case class Backup(wallet: Any)                        extends BackupMsg
  case class BackupAck()                                extends BackupMsg
  case class Restore()                                  extends BackupMsg
  case class Restored(wallet: WalletBackupEncoded)      extends BackupMsg
  case class BackupFailure(failure: PersistenceFailure) extends BackupMsg

  /**
    * Control messages
    */
  sealed trait BackupControl                      extends Control with MsgBase

  case class Init(params: Parameters)             extends BackupControl
  case class InitBackup(params: BackupInitParams) extends BackupControl
  //TODO: RTM - when version upgrades are available, 0.1 should be Array[Byte] and 0.2 should be base65 encoded string
  case class ExportBackup(wallet: Any)            extends BackupControl
  case class RecoverBackup()                      extends BackupControl
  case class RecoveryKeyRegistered()              extends BackupControl
  case class FailedToRegisterRecoveryKey()        extends BackupControl
  case class RestoreBackup(recoveryVk: VerKey)    extends BackupControl

  /**
    * Driver Messages
    */
  sealed trait BackupSignal

  case class ProvideRecoveryDetails(params: BackupInitParams) extends BackupSignal
  case class ReportBackupFailure(failure: PersistenceFailure) extends BackupSignal

  type WalletBackupIntList = List[Int]
  type WalletBackup = Array[Byte]
  type WalletBackupEncoded = Base64Encoded
}
