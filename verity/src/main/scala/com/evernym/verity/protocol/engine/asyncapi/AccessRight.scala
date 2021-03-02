package com.evernym.verity.protocol.engine.asyncapi

sealed trait AccessRight

sealed trait WalletAccessRight extends AccessRight

case object AccessNewDid extends WalletAccessRight
case object AccessSign extends WalletAccessRight
case object AccessVerify extends WalletAccessRight
case object AccessVerKey extends WalletAccessRight
// Pack and Unpack refer to libindy's packing feature using anoncrypt or authcrypt
case object AccessPack extends WalletAccessRight
case object AccessUnPack extends WalletAccessRight
case object AccessStoreTheirDiD extends WalletAccessRight

case object AnonCreds extends WalletAccessRight

sealed trait LedgerAccessRight extends AccessRight

case object LedgerWriteAccess extends LedgerAccessRight
case object LedgerReadAccess extends LedgerAccessRight

case object UrlShorteningAccess extends AccessRight
