package com.evernym.verity.libindy

import akka.actor.ActorRef
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.{ActorSpec, TestAppConfig}
import com.evernym.verity.actor.wallet.{Close, CreateNewKey, CreateWallet, NewKeyCreated, WalletCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.wallet.WalletAccessAPI
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessPack, AccessRight, AccessSign, AccessStoreTheirDiD, AccessUnPack, AccessVerKey, AccessVerify, AnonCreds, AsyncOpRunner}
import com.evernym.verity.protocol.engine.asyncapi.wallet.{InvalidSignType, WalletAccessAdapter}
import com.evernym.verity.protocol.engine.ParticipantId
import com.evernym.verity.testkit.{BasicSpec, HasDefaultTestWallet}
import com.evernym.verity.util.{ParticipantUtil, TestExecutionContextProvider}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WalletAccessAPISpec
  extends BasicSpec
    with ActorSpec
    with HasDefaultTestWallet
    with AsyncOpRunner {

  implicit def asyncAPIContext: AsyncAPIContext =
    AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)

  implicit def asyncOpRunner: AsyncOpRunner = this
  val selfParticipantId: ParticipantId = {
    testWalletAPI.executeSync[WalletCreated.type](CreateWallet())
    val result = ParticipantUtil.participantId(
      testWalletAPI.executeSync[NewKeyCreated](CreateNewKey()).did, None)
    testWalletAPI.executeSync[Done.type](Close())
    result
  }

  val walletRights: Set[AccessRight] =
    Set(AccessNewDid, AccessSign, AccessVerify, AccessVerKey, AccessPack, AccessUnPack, AccessStoreTheirDiD, AnonCreds)
  val walletAccess = new WalletAccessAdapter(new WalletAccessAPI(walletAPI, selfParticipantId))

  val TEST_MSG: Array[Byte] = "test string".getBytes()
  val INVALID_SIGN_TYPE = "Invalid sign type"

  var did: DidStr = _
  var verKey: VerKeyStr = _
  var signature: Array[Byte] = _

  "WalletAccessLibindy newDid" - {
    "should succeed" in {
      walletAccess.newDid() {
        case Success(keyCreated) =>
          did = keyCreated.did
          verKey = keyCreated.verKey
        case Failure(cause) =>
          fail(cause)
      }
    }
  }

  "WalletAccessLibindy sign" - {
    "sign of data should succeed" in {
      walletAccess.sign(TEST_MSG) {
        case Success(signedMsg) => signature = signedMsg.signatureResult.signature
        case Failure(cause) =>
          fail(cause)
      }
    }

    "sign request with invalid sign type should fail" in {
      walletAccess.sign(TEST_MSG, INVALID_SIGN_TYPE) { result =>
        result shouldBe Failure(InvalidSignType(INVALID_SIGN_TYPE))
      }
    }
  }

  "WalletAccessLibindy verify" - {
    "verify request with correct signature should succeed" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, signature) { result =>
        result shouldBe Success(true)
      }
    }

    "verify request with modified msg signature should return false" in {
      walletAccess.verify(selfParticipantId, "modified msg".getBytes(), signature) { result =>
        result shouldBe Success(false)
      }
    }

    "verify request with invalid data should fail" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, "short sig".getBytes()) { result =>
        result.isSuccess shouldBe false
      }
    }

    "verify request with invalid VerKey used should return false" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, signature, Some(verKey)) { result =>
        result shouldBe Success(false)
      }
    }

    "verify request with invalid sign type should fail" in {
      walletAccess.verify(selfParticipantId, TEST_MSG, signature, None, INVALID_SIGN_TYPE) { result =>
        result shouldBe Failure(InvalidSignType(INVALID_SIGN_TYPE))
      }
    }
  }

  override def runAsyncOp(op: => Any): Unit = op

  override def runFutureAsyncOp(op: => Future[Any]): Unit = op
  override def abortTransaction(): Unit = {}
  def postAllAsyncOpsCompleted(): Unit = {}
  override def logger: Logger = getLoggerByName(getClass.getSimpleName)
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
