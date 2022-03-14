package com.evernym.verity.protocol.protocols.walletBackup

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.{CanGenerateDid, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.journal.DebugProtocols
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.Bucket_2_Legacy
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupMsgFamily._
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{SimpleProtocolSystem, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}
import org.scalatest.concurrent.Eventually

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


class WalletBackupSpec()
  extends TestsWalletBackup
    with BasicFixtureSpec
    with DebugProtocols
    with Eventually {

  import BackupSpecVars._
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp

  override val defaultInitParams = Map(
    DATA_RETENTION_POLICY -> "30 day"
  )

  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  "A wallet backup protocol" - {

    "init tests" - {
      "when sending a FailedToRegisterRecoveryKey to the persister" - {
        "should result in FailedToRegisterRecoveryKey" in { f =>
          (f(EXPORTER) engage f(PERSISTER)) ~ InitBackup(BACKUP_INIT_PARAMS)

          intercept[UnableToSetupRecoveryKey] {
            f(PERSISTER) ~ FailedToRegisterRecoveryKey()
          }
        }
      }

      "when persister is provisioned" - {
        "should have state RequestedRecoveryKeySetup before driver responds" in { f =>
          (f(EXPORTER) engage f(PERSISTER)) ~ InitBackup(BACKUP_INIT_PARAMS)
          f(PERSISTER).state shouldBe State.RecoveryModeRequested(RECOVERY_VK)
          f(EXPORTER).state shouldBe State.BackupInitRequested()
        }
      }

      "when persister registers recovery key" - {
        "should result in roles being set and WalletInitialized" in autoRegisteredVk { f =>
          f(EXPORTER).state shouldBe a[State.ReadyToExportBackup]
          f(EXPORTER).role shouldBe Exporter

          f(PERSISTER).state shouldBe a[State.ReadyToPersistBackupRef]
          f(PERSISTER).role shouldBe Persister
        }
      }

    }

    "uploading tests" - {
      "when backup is uploaded" - {
        "should result in BACKUP_STORED state for wallet provider and receiver" in autoRegisteredVk { f =>
          f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)

          f(EXPORTER).state shouldBe a[State.ReadyToExportBackup]

          f(PERSISTER).state shouldBe a[State.ReadyToPersistBackupRef]
        }

        "should store wallet (byte array)" in autoRegisteredVk { f =>
          f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)

          f(PERSISTER) expect signal[ProvideRecoveryDetails]
          assertStoreByRecovering(f(EXPORTER), DEFAULT_WALLET)
        }

        "should store wallet by passing base64 encoded string" in autoRegisteredVk { f =>
          f(EXPORTER) ~ ExportBackup(ENCODED_WALLET)

          f(PERSISTER) expect signal[ProvideRecoveryDetails]
          assertStoreByRecovering(f(EXPORTER), Base64Util.getBase64Decoded(ENCODED_WALLET))
        }

        "should store wallet by passing byte list" in autoRegisteredVk { f =>
          val wallet: List[Byte] = List(0, 22, 3)
          f(EXPORTER) ~ ExportBackup(wallet.map(_.toInt))

          f(PERSISTER) expect signal[ProvideRecoveryDetails]
          assertStoreByRecovering(f(EXPORTER), wallet.toArray)
        }

        "should throw unsupported type if not base64 encoded string or Array[Byte]" in autoRegisteredVk { f =>
          val unsupportedType: Array[Double] = Array(1.0, 2.0, 3.0)
          intercept[UnsupportedBackupType] {
            f(EXPORTER) ~ ExportBackup(unsupportedType)
          }
        }

        "should be able to overwrite with new wallet (byte array)" in autoRegisteredVk { f =>
          val wallet1: Array[Byte] = Array(4)
          val wallet2: Array[Byte] = Array(1, 3, 5)

          f(EXPORTER) ~ ExportBackup(wallet1)
          f(PERSISTER) expect signal[ProvideRecoveryDetails]
          assertStoreByRecovering(f(EXPORTER), wallet1)

          // Overwrite wallet
          f(EXPORTER) ~ ExportBackup(wallet2)
          assertStoreByRecovering(f(EXPORTER), wallet2)

          f(PERSISTER).state.asInstanceOf[State.ReadyToPersistBackupRef]
        }

        "should fail if backup recovery is in progress" in autoRegisteredVk { f =>
          f(EXPORTER).state shouldBe a[State.ReadyToExportBackup]
          f(EXPORTER).container_!.state = State.RecoveringBackup()

          intercept[UnableToBackup] {
            f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)
          }
        }
      }

      "when exporter loses its state" - {
        "should be able to reprovision and still access same wallet" in { f =>

          interaction(f(EXPORTER), f(PERSISTER)) {

            f(EXPORTER) ~ InitBackup(BACKUP_INIT_PARAMS)
            f(PERSISTER) ~ RecoveryKeyRegistered()

            f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)

            f(PERSISTER) expect signal[ProvideRecoveryDetails]
            assertStoreByRecovering(f(EXPORTER), DEFAULT_WALLET)

            f(PERSISTER).state shouldBe a[State.ReadyToPersistBackupRef]

          }

          // New relationship (exporter) with same cloud agent (provisioner)

          val did = didFor(f(EXPORTER).domain, f(PERSISTER).domain)

          f.sys.deregister(f.sys.containerForParti_!(did))

          f.sys.removeRoute(EXPORTER)
          f.sys.removeRoute(did) //TODO-rk: had to add this to make it pass, don't know yet, why

          val newExporter = setup(EXPORTER)(f.sys)

          interaction(newExporter, f(PERSISTER)) {

            f(PERSISTER).state shouldBe a[State.ReadyToPersistBackupRef]
            f(PERSISTER).role shouldBe Persister

            newExporter ~ InitBackup(BACKUP_INIT_PARAMS)

            eventually {
              newExporter.state shouldBe a[State.ReadyToExportBackup]
            }

            f(PERSISTER).state shouldBe a[State.ReadyToPersistBackupRef]

            newExporter ~ RecoverBackup()

            ensureBackupSuccess(backupSignaledFromExporter(newExporter), DEFAULT_WALLET)
          }
        }
      }
    }

    "recovery tests" - {
      "when Exporter tries to recover backup" - {
        "should fail with no wallet" in autoRegisteredVk { f =>
          intercept[NoBackupAvailable] {
            f(EXPORTER) ~ RecoverBackup()
          }
        }

        "should recover wallet after backup" in autoRegisteredVk { f =>
          f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)

          f(PERSISTER) expect signal[ProvideRecoveryDetails]
          assertStoreByRecovering(f(EXPORTER), DEFAULT_WALLET)

          f(EXPORTER) ~ RecoverBackup()

          ensureBackupSuccess(backupSignaledFromExporter(f(EXPORTER)), DEFAULT_WALLET)
        }

        "should not be able to restore when a backup is pending" in autoRegisteredVk { wb =>
          wb(EXPORTER).container_!.state = State.BackupInProgress()

          intercept[UnableToRecoverBackup] {
            wb(EXPORTER) ~ RecoverBackup()
          }
        }
      }

      "when Recoverer recovers backup" - {
        "should provision and recover wallet" in { f =>

          playExt(f(EXPORTER) -> "exporter-did", f(PERSISTER) -> "persister-did") {
            f(EXPORTER) ~ InitBackup(BACKUP_INIT_PARAMS)

            f(PERSISTER) ~ RecoveryKeyRegistered()

            f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)

            f(PERSISTER) expect signal[ProvideRecoveryDetails]
            assertStoreByRecovering(f(EXPORTER), DEFAULT_WALLET)
          }

          f.sys.deregister(f.sys.containerForParti_!("exporter-did"))

          f.sys.removeRoute(EXPORTER)
          val newExporter = setup(EXPORTER)(f.sys)

          playExt(newExporter -> RECOVERY_VK, f(PERSISTER) -> "persister-did") {
            //TODO need to force RECOVERY_VK like this
            //  RECOVERER -> newContainer(s, partiId = RECOVERY_VK),

            //TODO ensure we have the same pinst id like this
            //  val p = wr(PERSISTER).container_!
            //  PERSISTER -> newContainer(s, partiId = p.participantId, pinstId = p.pinstId, recorder = Some(p.eventRecorder))

            newExporter ~ RestoreBackup(RECOVERY_VK)

            newExporter.role shouldBe Recoverer
            newExporter.state shouldBe a[State.Recovered]
          }
        }
      }
    }

    "bad state tests" - {
      "when exporter receives unexpected message" - {
        "should throw UnexpectedProtoMessage for unexpected protocol messages" in autoRegisteredVk { f =>
          def assertBadExporterStates(persister: Container) = {
            intercept[UnexpectedProtoMsg] {
              sendProtoMsg(persister, f(EXPORTER).container_!, BackupReady())
            }

            intercept[UnexpectedProtoMsg] {
              sendProtoMsg(f(PERSISTER).container_!, f(EXPORTER).container_!, BackupAck())
            }
          }

          // READY_TO_EXPORT
          assertBadExporterStates(f(PERSISTER).container_!)

          // BACKUP_STORED
          f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)

          assertBadExporterStates(f(PERSISTER).container_!)
        }

        "should throw UnexpectedProtoMessage when in incorrect state for BackupProvisioned" in autoRegisteredVk { f =>
          val unacceptableStates = Array(State.Initialized, State.ReadyToExportBackup, State.BackupInProgress)

          for (state <- unacceptableStates) {
            f(EXPORTER).container_!.state = state()
            intercept[UnexpectedProtoMsg] {
              sendProtoMsg(f(PERSISTER).container_!, f(EXPORTER).container_!, BackupReady())
            }
          }
        }

        "should throw UnexpectedProtoMessage when in incorrect state for BackupAck" in autoRegisteredVk { f =>
          val unacceptableStates = Array(State.Initialized, State.BackupInitRequested, State.ReadyToExportBackup)

          for (state <- unacceptableStates) {
            f(EXPORTER).container_!.state = state()
            intercept[UnexpectedProtoMsg] {
              sendProtoMsg(f(PERSISTER).container_!, f(EXPORTER).container_!, BackupAck())
            }
          }
        }
      }

      // Exporter Ctl Message Errors (Just ExportBackup)
      "when exporter sends ExportBackup ctl message in incorrect state" - {
        "should throw UnableToBackup" in autoRegisteredVk { f =>

          val unacceptableStates = Array(State.Uninitialized, State.Initialized,
            State.BackupInitRequested, State.BackupInProgress)

          for (state <- unacceptableStates) {
            f(EXPORTER).container_!.state = state()
            intercept[UnableToBackup] {
              f(EXPORTER) ~ ExportBackup(DEFAULT_WALLET)
            }
          }
        }
      }

      // Persister Protocol Message errors
      "when persister receives Backup protocol message in incorrect state" - {
        "should throw UnableToPersist" in autoRegisteredVk { f =>
          f(PERSISTER).container_!.state = State.Initialized()
          intercept[UnableToPersist] {
            sendProtoMsg(f(EXPORTER).container_!, f(PERSISTER).container_!, Backup(DEFAULT_WALLET))
          }
        }
      }
    }
  }

  def assertStoreByRecovering(exporter: TestEnvir, expectedWallet: WalletBackupBytes): Unit = {
    exporter ~ RecoverBackup()
    ensureBackupSuccess(backupSignaledFromExporter(exporter), expectedWallet)
  }


  def backupSignaledFromExporter(exporter: TestEnvir): WalletBackupBytes =
    Base64Util.getBase64Decoded((exporter expect signal[Restored]).wallet)

  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}

abstract class TestsWalletBackup extends TestsProtocolsImpl(WalletBackupProtoDef, Option(Bucket_2_Legacy)) {

  import com.evernym.verity.protocol.protocols.walletBackup.BackupSpecVars._

  def appConfig: AppConfig
  def futureExecutionContext: ExecutionContext

  override val containerNames: Set[ContainerName] = Set(EXPORTER, PERSISTER, RECOVERER)

  def autoRegisteredVk[T](func: Scenario => T): Scenario => T = { f =>
    interaction(f(EXPORTER), f(PERSISTER)) {
      f(EXPORTER) ~ InitBackup(BACKUP_INIT_PARAMS)
      f(PERSISTER) ~ RecoveryKeyRegistered()
      func(f)
    }
  }

  def recoverer(te: TestEnvir): Scenario = {
    val p = te.container_!
    implicit val s = new SimpleProtocolSystem()

    //FIXME JL to RM: we need to move this to the new testing approach (containers created indirectly)
    val containers = Map(
      RECOVERER -> newContainer(s, futureExecutionContext, appConfig, partiId = RECOVERY_VK),
      PERSISTER -> newContainer(s, futureExecutionContext, appConfig, partiId = p.participantId, pinstId = p.pinstId, recorder = Some(p.eventRecorder))
    )

    Scenario(RECOVERER, PERSISTER)
  }

  def ensureBackupSuccess(retrievedWallet: WalletBackupBytes, originalWallet: WalletBackupBytes): Unit =
    assert(retrievedWallet sameElements originalWallet)
}

object BackupSpecVars extends CanGenerateDid {
  val EXPORTER = "exporter"
  val PERSISTER = "persister"
  val RECOVERER = "recoverer"
  val RECOVERY_VK: VerKeyStr = generateNewDid().verKey
  val DD_ADDRESS = "456"
  val CLOUD_ADDRESS = "789"
  val BACKUP_INIT_PARAMS: BackupInitParams = BackupInitParams(RECOVERY_VK, DD_ADDRESS, CLOUD_ADDRESS.getBytes())
  val DEFAULT_WALLET: Array[Byte] = Array(0, 1, 2, 3)
  val ENCODED_WALLET: String = Base64Util.getBase64Encoded(Array(0, 1, 2, 3))
  val THROWABLE_TEST: Throwable = new Throwable
}


