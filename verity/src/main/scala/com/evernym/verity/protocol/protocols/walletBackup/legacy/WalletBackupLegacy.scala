package com.evernym.verity.protocol.protocols.walletBackup.legacy

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.StoredSegment
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{Protocol, ProtocolContextApi}
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupMsgFamily.{BackupMsg, _}
import com.evernym.verity.protocol.protocols.walletBackup.{BackupState, State => S, _}
import com.evernym.verity.util.Base64Util.{getBase64Decoded, getBase64Encoded}
import com.google.protobuf.ByteString

import scala.util.{Failure, Success}

trait WalletBackupLegacy extends Protocol[WalletBackup, Role, BackupMsg, BackupEvt, BackupState, String]
  with ProtocolHelpers[WalletBackup, Role, BackupMsg, BackupEvt, BackupState, String] {

  override type Context = ProtocolContextApi[WalletBackup, Role, BackupMsg, BackupEvt, BackupState, String]
  implicit val ctx: Context

  def legacyRestoreProto: (BackupState, Option[Role], BackupMsg) ?=> Any = {
    case (s: S.ReadyToPersistBackup      , Some(r)         , _: Restore    ) => recoverBackupLegacy(s.vk, r, s.lastWallet)
  }

  def legacyProtoHandler: (BackupState, BackupMsg) ?=> Any = {
    case (_: S.ReadyToPersistBackup   , BackupInit(_) )  => ctx.send(BackupReady())
    case (s: S.ReadyToPersistBackup   , Backup(w)     )  => backupDup(s.vk, w)
    case (_                           , Backup(_)     )  => throw new UnableToPersist
  }

  def legacyApplyEvt: ApplyEvent = {
    case (s: S.ReadyToPersistBackup    , _ , BackupStored(w)              ) => S.ReadyToPersistBackup(s.vk, Some(w.toByteArray))
    case (_: S.ReadyToPersistBackup    , _ , RecoveredBackup()            ) => ctx.getState
    case (s: S, _, e) =>
      //NOTE:
      // As of today this protocol is not used, but a scheduled job was trying to spin up this older protocol
      // and it was causing unhandled error which was changing app state to Sick unnecessarily.
      // This block is to handle those older events which is no more backward compatible with new code
      logger.warn(s"[${definition.msgFamily.protoRef.toString}] unhandled event '${e.getClass.getSimpleName}' in state: '${s.getClass.getSimpleName}' " +
        s"(this is known issue for very old non-backward compatible 'wallet-backup' protocol events)")
      s
  }

  //TODO: This is a duplicate function - in WalletBackup.scala
  def backupDup(vk: VerKeyStr, wallet: Any): Unit = {
    val w: WalletBackupBytes = wallet match {
      case w: WalletBackupBytes => ctx.logger.debug("byte array received - newer expectation is base64 encoded str"); w
      case w: WalletBackupEncoded => ctx.logger.debug("received base64 encoded string"); getBase64Decoded(w)
      case w: List[_] =>
        ctx.logger.debug("Int list received - newer expectation is base64 encoded str")
        // Can't test for List[Int] but that is what is expected
        // So we will cast to List[Int] even though we don't know for sure it is of that type
        val l = w.asInstanceOf[List[Int]]
        l.map(_.toByte).toArray
      case _ => throw new UnsupportedBackupType
    }

    ctx.storeSegment(vk, BackupStored(ByteString.copyFrom(w))) {
      case Success(_: StoredSegment) =>
        ctx.apply(BackupStoredRef(vk))
        ctx.send(BackupAck())
      case Failure(e) =>  throw new UnableToBackup(Some(e.getMessage))
    }
  }

  def recoverBackupLegacy(blobAddress: VerKeyStr, r: Role, w: Option[WalletBackupBytes]=None): Unit = {
    def restored(b: WalletBackupBytes): Restored = Restored(getBase64Encoded(b))

    ctx.withSegment[BackupStored](blobAddress) {
      case Success(storedBackup: Option[BackupStored]) =>
        val backup = storedBackup
          .map(bs => restored(bs.wallet.toByteArray))
          .getOrElse(restored(w.getOrElse(throw new NoBackupAvailable)))
        ctx.apply(RecoveredBackup())
        ctx.send(backup, toRole=ctx.getRoster.roleForId(ctx.getRoster.participantIdForRole_!(r)))
      case Failure(exception) => throw exception
    }
  }
}
