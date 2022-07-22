package com.evernym.verity.vdrtools

import com.evernym.verity.util2.Status.{StatusDetail, StatusDetailException}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.ledger._
import com.evernym.verity.vdrtools.ledger.{IndyLedgerPoolConnManager, LedgerTxnExecutorV1, SubmitToLedger}
import com.evernym.verity.did.{DidStr, DidPair}
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.vdrtools.ErrorCode.PoolLedgerTimeout
import com.evernym.vdrtools.IndyException
import com.evernym.vdrtools.pool.Pool
import org.mockito.invocation.InvocationOnMock
import com.evernym.verity.util2.ExecutionContextProvider
import org.mockito.scalatest.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


class LedgerTxnExecutorV1Spec
  extends ActorSpec
    with BasicSpecWithIndyCleanup
    with MockitoSugar {

  val maxWaitTime: Duration = 50000.millis
  lazy val mockWalletAPI: WalletAPI = mock[WalletAPI]
  lazy val mockLedgerSubmitAPI: SubmitToLedger = mock[SubmitToLedger]
  lazy val poolConnManager: IndyLedgerPoolConnManager =
    new IndyLedgerPoolConnManager(system, appConfig, executionContext) {
      override def poolConn: Some[Pool] = Some(null)
    }
  lazy val ledgerTxnExecutor: LedgerTxnExecutorV1 =
    new LedgerTxnExecutorV1(system, appConfig, Some(mockWalletAPI), poolConnManager.poolConn, None, executionContext) {
      override def ledgerSubmitAPI:SubmitToLedger = mockLedgerSubmitAPI
    }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  lazy implicit val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val submitterDID: DidStr = "Th7MpTaRZVRYnPiabds81Y"
  implicit lazy val wap: WalletAPIParam =  WalletAPIParam(submitterDID)
  lazy val submitter: Submitter = Submitter(submitterDID, Some(wap))

  lazy val targetDidPair: DidPair = DidPair("VFN92wTpay26L64XnEQsfR", "GPxvxemamgNTYpe1J6J1ivr5qwsBDWFCxHHzZG67mhW3")


  "LedgerOperationManager" - {

    "when executed add nym operation" - {
      "and if ledger executes it successfully" - {
        "should return success response" in {
          val validResponse =
            """{"result":{"identifier":"Th7MpTaRZVRYnPiabds81Y","txnTime":1517112472,
              |"verkey":"~L6V21qHvonLitt24HhUbML","reqId":1517112471766131677,"dest":"TMXKky64ENZpw31SmnE2WD",
              |"type":"1","auditPath":["BmYJETcYyyeHYzyQ3so81FjgQmWRXt3o75q8HTVRoiag",
              |"FUUbzChmnGjrGChBv3LZoKunodBPrVuMcg2vUrhkndmz"],"rootHash":"6NeU3HbaRFL4coC9GdRSngMeR8K9TLGR43sXz7V4hQzV"
              |,"seqNo":11,"signature":
              |"4WHYs8676UXg2nZtT3ZjHyqwpCcbMJ9yaKESXe4yw1x3Bo96FujaSwWUnocrqMNzXUWJWfq3gWTSnTzdmarKkb3L"},
              |"op":"REPLY"}""".stripMargin
          doReturn(Future(validResponse))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          when(mockWalletAPI.executeAsync[LedgerRequest](any[SignLedgerRequest])(any[WalletAPIParam]))
            .thenAnswer ((i: InvocationOnMock) => Future(i.getArgument[SignLedgerRequest](0).request))
          val response = Await.ready(
            ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime
          ).value.get
          response match {
            case Success(resp) => resp shouldBe a[Unit]
            case x => x should not be x
          }
        }
      }

      "and if ledger responds with error" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog) in {
          val invalidResponses = List(
            """{"identifier":"TMXKky64ENZpw31SmnE2WD,"reqId":1517113573308345681,
              |"reason":"client request invalid: InvalidSignature()","op":"REQNACK"}""".stripMargin)
          invalidResponses.foreach { ivr =>
            doReturn(Future(ivr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.ready(
              ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime
            ).value.get
            response match {
              case Failure(StatusDetailException(resp)) => resp shouldBe a[StatusDetail]
              case x => x should not be x
            }
          }
        }
      }

      "and if underlying wallet api throw an exception" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog) in {
          doReturn(Future.failed(IndyException.fromSdkError(PoolLedgerTimeout.value)))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          val response = Await.ready(
            ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime
          ).value.get
          response match {
            case Failure(StatusDetailException(resp)) => resp shouldBe a[StatusDetail]
            case x => x should not be x
          }
        }
      }
    }

    "when executed add attr operation" - {
      "and if ledger executes it successfully" - {
        "should return success response" in {
          val validResponse =
            """{"result":{"type":"100","reqId":1530159819906096868,"rootHash":"3QqkXDa3T35oDUtKiX9nfYZuJLzGXxcA7Bqyt2Hb4nJd","raw":"{\"url\":\"testvalue\"}","txnTime":1530159820,"signature":"pdC1yghHd4dUmm6WDE9k85Aw73qaxBYZRJWe5oyf1cYCSBiMbkn5pW68a57TaENnWGedcu5y35QzHW89DVuM864","auditPath":["75RMSGKTRdknTSWkSVtGTuLiNBXVUn4pZGdMqFo3vVVi","37tAEcNxXHSnNhZR21YgsCrXbxhnFwjkHyhdFf7qB8Kz","HXMqv1xpDt4xMbHdSUFPUyLsC8LVKeFj4oTWC5jMuRHH","7WNdgAkQzhnV2LcHPynDc6NPSrqipiZr2vP4zYDGzWKR"],"signatures":null,"identifier":"YYBAxNYvjR1D9aL6GMr3vK","seqNo":3589,"dest":"YYBAxNYvjR1D9aL6GMr3vK"},"op":"REPLY"}""".stripMargin
          doReturn(Future(validResponse))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          val response = Await.ready(
            ledgerTxnExecutor.addAttrib(
              submitter,
              "VFN92wTpay26L64XnEQsfR",
              "url",
              "http:test"
            ), maxWaitTime
          ).value.get
          response match {
            case Success(resp) => resp shouldBe a[Unit]
            case x => x should not be x
          }
        }
      }
    }
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}
