package com.evernym.verity.vdrtools

import com.evernym.verity.util2.Status.{StatusDetail, StatusDetailException, TAA_NOT_SET_ON_THE_LEDGER}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.ledger._
import com.evernym.verity.vdrtools.ledger.{IndyLedgerPoolConnManager, LedgerTxnExecutorV2, SubmitToLedger}
import com.evernym.verity.did.{DidStr, DidPair}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerRejectException
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault._
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.vdrtools.pool.Pool
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


class LedgerTxnExecutorV2Spec
  extends ActorSpec
    with BasicSpecWithIndyCleanup
    with MockitoSugar {

  val maxWaitTime: Duration = 5000.millis
  lazy val mockWalletAPI: WalletAPI = mock[WalletAPI]
  lazy val mockLedgerSubmitAPI: SubmitToLedger = mock[SubmitToLedger]
  lazy val poolConnManager: IndyLedgerPoolConnManager =
    new IndyLedgerPoolConnManager(system, appConfig, executionContext) {
      override def poolConn: Some[Pool] = Some(null)
    }
  lazy val ledgerTxnExecutor: LedgerTxnExecutorV2 =
    new LedgerTxnExecutorV2(system, appConfig, Some(mockWalletAPI), poolConnManager.poolConn, None, executionContext) {
      override def ledgerSubmitAPI:SubmitToLedger = mockLedgerSubmitAPI
    }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  lazy implicit val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val submitterDID: DidStr = "Th7MpTaRZVRYnPiabds81Y"
  lazy val wap: WalletAPIParam = WalletAPIParam(submitterDID)
  lazy val submitter: Submitter = Submitter(submitterDID, Some(wap))

  lazy val targetDidPair: DidPair = DidPair("VFN92wTpay26L64XnEQsfR", "GPxvxemamgNTYpe1J6J1ivr5qwsBDWFCxHHzZG67mhW3")


  "LedgerOperationManager" - {

    "when executed add nym operation" - {
      "and if ledger executes it successfully" - {
        "should return success response" taggedAs UNSAFE_IgnoreLog in {
          val validResponse =
            """{"result":{"ver":"1","txn":{"metadata":{"digest":"bf6ad32c57789d763cb989f6e1d14aa8a89949fc99a07aa94cc85f2acf7cd411","reqId":1532330281707005000,"from":"Th7MpTaRZVRYnPiabds81Y"},"protocolVersion":2,"type":"1","data":{"dest":"3pB1JfL2BHNjmaUj7bbJTn","verkey":"2XwCs4gdFMKmfy23iQoFTrfewAWjnrGYmxAE7kxGR5Mz"}},"rootHash":"JBgBjiiJpD6f7Vv6VSnenCPdDvB5QWNN8Esa8vRiczzj","reqSignature":{"type":"ED25519","values":[{"value":"42XcgJQQ8sP9ibds2eEcq2yLMjUMDmnzFHxzH5ARTBjTH1sqdstydzTfUHavhPHEhNjciv67LJEupYnUAdbFrS6T","from":"Th7MpTaRZVRYnPiabds81Y"}]},"txnMetadata":{"txnId":"b4680ea46a7975006627977d624f07c9bf3cbbb3ccf3ffa92b83d8304d0dcbfc","seqNo":448,"txnTime":1532330281},"auditPath":["GfsuEw7NyYvTWhB2fnaX9jd7xkBf2sxcbmXq4hWg6w6v","H9mCzs2g2DF2AxmfxLb3VUndQEYFoVe7366TEUoukGhb","CA3gxxWgRF4SU5E5dghdLpw87P3KkT4xBYX9E22tVxnx","65kcUqw29WDPeWEe3R8eQHUDrh1dzUV2sf8AYjxypMd6","DJN2rWacgFzhgsJReD5EgSJzEFeCuU7nsvX3SdJaZSmv","7Cd531U9orb3S6sSCtMFPCZhETRyP3DR1msnz3j9LLh9","37cJ9zbeoCmn8pYm4oWaDDQ9zsWLNp4aJhheKBATAfq6","SM4p1s81TX1hLr1EMbwxPxSo3mt5SGYJkC3zsmL8WJC"]},"op":"REPLY"}""".stripMargin
          doReturn(Future(validResponse))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          when(mockWalletAPI.executeAsync[LedgerRequest](any[SignLedgerRequest])(any[WalletAPIParam]))
            .thenAnswer ((i: InvocationOnMock) => Future(i.getArgument[SignLedgerRequest](0).request))
          val response = Await.ready(
            ledgerTxnExecutor.addNym(submitter, targetDidPair),
            maxWaitTime
          ).value.get
          response match {
            case Success(resp) => resp shouldBe a[Unit]
            case x => x should not be x
          }
        }
      }

      "and if ledger response with reject" - {
        "throw an exception" in {
          val validResponse =
            """{"reqId":1532330333807668000,"identifier":"Th7MpTaRZVRYnPiabds81Y","op":"REJECT","reason":"client request invalid: UnauthorizedClientRequest('Th7MpTaRZVRYnPiabds81Y is neither Trustee nor owner of 3pB1JfL2BHNjmaUj7bbJTn',)"}"""
          doReturn(Future(validResponse))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          an [LedgerRejectException] should be thrownBy Await.result(
            ledgerTxnExecutor.addNym(submitter, targetDidPair),
            maxWaitTime
          )
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
            val response = Await.ready(ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime).value.get
            response match {
              case Failure(StatusDetailException(resp)) => resp shouldBe a[StatusDetail]
              case x => x should not be x
            }
          }
        }
      }

      "and if underlying wallet api throw an exception" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog) in {
          val response = Await.ready(ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime).value.get
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
            """{"result":{"txnMetadata":{"seqNo":12,"txnTime":1530159597},"reqSignature":{"type":"ED25519","values":[{"value":"59Z4PdmsgP7NUxDfJ1ZgtNPHMGhSkcqo8u5vtwoc6khAekVRgVrnYnon76yxs4wTvnbAQphvMHLjAx2Pyeoc6yn5","from":"PJwbFcdVdjbKVJ2q7N9dJn"}]},"rootHash":"7DCA8vGLifZogvvEuUgdv3TsPGo2a5d6Xr2GubpuwS2p","txn":{"type":"100","protocolVersion":2,"data":{"raw":"{\"url\":\"testvalue\"}","dest":"PJwbFcdVdjbKVJ2q7N9dJn"},"metadata":{"from":"PJwbFcdVdjbKVJ2q7N9dJn","digest":"bc29ec50c53c5fab35a46b257594cbb22449d26645455d18b799f83749e2e0df","reqId":1530159597177549776}},"ver":"1","auditPath":["8Aa3aYzhpbWPMum76tT252D145frVd39SiGkCEYbyqyQ","EsY4hbw8MPXuyQTiq43pvwJqak6pGzfKwJKMXoi6uYS7","DNHM372JZJoGcxdHdmsj3QSSiomyeZux6ssJXxAJqyvd"]},"op":"REPLY"}""".stripMargin
          doReturn(Future(validResponse))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          val response = Await.ready(ledgerTxnExecutor.addAttrib(submitter, "VFN92wTpay26L64XnEQsfR", "url", "http:test"), maxWaitTime).value.get
          response match {
            case Success(resp) => resp shouldBe a[Unit]
            case x => x should not be x
          }
        }
      }
    }

    "when executed get TAA operation" - {
      "if ledger responds with valid response" - {
        "should return success response" in {
          val validResponses = List("""{
   "result":{
      "txnTime":1574452896,
      "type":"6",
      "reqId":1,
      "identifier":"V4SGRU86Z58d6TV7PBUe6f",
      "data":{
         "version":"1.0.0",
         "text":"TAA for sandbox ledger"
      },
      "dest":"V4SGRU86Z58d6TV7PBUe6f",
      "state_proof":{
         "proof_nodes":"+QGQ+E2GIGF0ZXN0uET4QrhAYTBhYjBhYWRhNzU4MmQ0ZDIxMWJmOTM1NWYzNjQ4MmU1Y2IzM2VlYjQ2NTAyYTcxZTZjYzdmZWE1N2JiODMwNfhRgICAgKCj66apQmo5b7HbT3Y19U7txqgNLwe6L38bYZOJ5gF1ZYCAgICAgICg2AdUfMspzXARYGfv3eBeOCSVw+5hhdOfW4541E5pawKAgICA5IIAOqA4aH6vfptblbQUMvN8fKQvJ2+iJ8A3MkCNVqtuWtYSWPhRgICAgICAoOK5ArEE0s7Q1Xv4hxupZUEo6BCEmygQiy999UiTVKLzoMMCJeiI7Isp1aZG\/H7cNTGTlQxIoYcaLvSjwb3zcP9WgICAgICAgICA+FGAgKClyk7RWtL\/kqryy39jjKs4oIVdFo7YsKDQcxPdW7P3hqBynv18UW8j3ffdsnPUjJ8ayou4vCe5cXW8qe+RbUKq24CAgICAgICAgICAgIDiE6AF7t3IKfR34fQMBxXr68rRwodl5Ly0c+jnUc67Y27Fog==",
         "multi_signature":{
            "signature":"RLEX3Urw1p7xtvqGhRUVUYy5UUBhrckuHDcCu6y5hcy6oMBn6kFotXnTNcoYFYFnyxvBu5XJKJX6F4oLyiSv14S7DRLpPu5ZZdnKEVStsKZJVTcpPVeHHZnzeXYkxy5wnoX15BFBfm1gxBsmVNUU4zEyigkrV26MbCHzbQ9WWXHQv3",
            "participants":[
               "Node2",
               "Node4",
               "Node1"
            ],
            "value":{
               "timestamp":1574453197,
               "pool_state_root_hash":"3qNHijEuuxwsZPnWQd3JF9obMcWHgU1WEB92XAFnC74U",
               "txn_root_hash":"2vm2uTggzr71VCbMeiinFMZiArBfARAaAFeMkSd88uSB",
               "ledger_id":2,
               "state_root_hash":"YNoujDrL1fha1stmNN9LCT6xLoBSxzhLKULiCu3cybM"
            }
         },
         "root_hash":"YNoujDrL1fha1stmNN9LCT6xLoBSxzhLKULiCu3cybM"
      },
      "seqNo":2
   },
   "op":"REPLY"
}""")
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.ready(ledgerTxnExecutor.getTAA(submitter), maxWaitTime).value.get
            response match {
              case Success(resp) => resp shouldBe a[GetTAAResp]
              case x => x should not be x
            }
          }
        }
      }

      "and if ledger responds with null data" - {
        "should throw an exception" in {
          val validResponsesWithNullData = List(
            """{"result":{"data":null,"seqNo":null,"dest":"7cGUMpETdhE45Sa2A36vGb","reqId":1530159593444617377,"identifier":"PJwbFcdVdjbKVJ2q7N9dJn","type":"6","txnTime":null},"op":"REPLY"}"""
          )
          validResponsesWithNullData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.ready(ledgerTxnExecutor.getTAA(submitter), maxWaitTime).value.get
            response match {
              case Failure(StatusDetailException(status)) => status shouldBe TAA_NOT_SET_ON_THE_LEDGER
              case x => x should not be x
            }
          }

        }
      }

      "and if ledger responds with no data field" - {
        "should return TAA_NOT_SET_ON_THE_LEDGER status" in {
          val validResponsesWithNoData = List(
            """{"result":{"seqNo":1,"dest":"7cGUMpETdhE45Sa2A36vGb","reqId":1530159593444617377,"identifier":"PJwbFcdVdjbKVJ2q7N9dJn","type":"6","txnTime":1},"op":"REPLY"}"""
          )
          validResponsesWithNoData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.ready(ledgerTxnExecutor.getTAA(submitter), maxWaitTime).value.get
            response match {
              case Failure(StatusDetailException(status)) => status shouldBe TAA_NOT_SET_ON_THE_LEDGER
              case x => x should not be x
            }
          }
        }
      }

      "and if ledger responds with invalid json" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog) in {
          val invalidResponses = List("", """{"res":""""")
          invalidResponses.foreach { ivr =>
            doReturn(Future(ivr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.ready(ledgerTxnExecutor.getTAA(submitter), maxWaitTime).value.get
            response match {
              case Failure(StatusDetailException(resp)) => resp shouldBe a[StatusDetail]
              case x => x should not be x
            }
          }
        }
      }

      "and if underlying wallet api throw an exception" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog)  in {
          val response = Await.ready(ledgerTxnExecutor.getTAA(submitter), maxWaitTime).value.get
          response match {
            case Failure(StatusDetailException(resp)) => resp shouldBe a[StatusDetail]
            case x => x should not be x
          }
        }
      }
    }
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}
