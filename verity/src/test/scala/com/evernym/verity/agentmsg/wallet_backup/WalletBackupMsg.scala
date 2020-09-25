package com.evernym.verity.agentmsg.wallet_backup

import com.evernym.verity.protocol.protocols.walletBackup.BackupInitParams

case class WalletBackupProvisionMsg(`@type`: String, params: BackupInitParams)
case class WalletBackupMsg(`@type`: String, wallet: Any)
case class WalletBackupRestoreMsg(`@type`: String)
