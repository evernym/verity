package com.evernym.verity.protocol.protocols.walletBackup

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine.Scope.ProtocolScope
import com.evernym.verity.protocol.engine._
import State.Uninitialized
import WalletBackupMsgFamily.{BackupMsg, Init}


/** Protocols are defined in pairs, a protocol definition, and a protocol
  * instance. This is a protocol definition for the Wallet backup protocol.
  *
  */
object WalletBackupProtoDef extends ProtocolDefinition[WalletBackupProtocol, Role, BackupMsg, BackupEvt, BackupState, String] {

  val msgFamily: MsgFamily = WalletBackupMsgFamily

  override def segmentedStateName: Option[String] = Option("backup")

  override def createInitMsg(params: Parameters) = Init(params)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID, DATA_RETENTION_POLICY)

  override val roles: Set[Role] = Set(Exporter, Persister, Recoverer)

  def create(ctx: ProtocolContextApi[WalletBackupProtocol, Role, BackupMsg, BackupEvt, BackupState, String]): WalletBackupProtocol = { //TODO can this be generically implemented in the base class?
    new WalletBackupProtocol(ctx)
  }

  override def initialState: BackupState = Uninitialized()

  override def scope: ProtocolScope = Scope.Agent
}

