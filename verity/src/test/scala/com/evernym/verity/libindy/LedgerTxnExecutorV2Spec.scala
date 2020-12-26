package com.evernym.verity.libindy

import com.evernym.verity.Exceptions.{InvalidValueException, MissingReqFieldException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{StatusDetail, TAA_NOT_SET_ON_THE_LEDGER}
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.ledger._
import com.evernym.verity.libindy.ledger.{IndyLedgerPoolConnManager, LedgerTxnExecutorV2, SubmitToLedger}
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.external_api_access.LedgerRejectException
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI
import org.hyperledger.indy.sdk.pool.Pool
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class LedgerTxnExecutorV2Spec extends ActorSpec
  with BasicSpecWithIndyCleanup with MockitoSugar {

  val maxWaitTime: Duration = 5000.millis
  lazy val mockWalletAPI: WalletAPI = mock[WalletAPI]
  lazy val mockLedgerSubmitAPI: SubmitToLedger = mock[SubmitToLedger]
  lazy val poolConnManager: IndyLedgerPoolConnManager = new IndyLedgerPoolConnManager(appConfig) {
    override def poolConn: Some[Pool] = Some(null)
  }
  lazy val ledgerTxnExecutor: LedgerTxnExecutorV2 = new LedgerTxnExecutorV2(appConfig, Some(mockWalletAPI), poolConnManager.poolConn, None) {
    override def ledgerSubmitAPI:SubmitToLedger = mockLedgerSubmitAPI
  }

  lazy val submitterDID: DID = "Th7MpTaRZVRYnPiabds81Y"
  lazy val wap: WalletAPIParam = WalletAPIParam(submitterDID)
  lazy val submitter: Submitter = Submitter(submitterDID, Some(wap))

  lazy val targetDidPair: DidPair = DidPair("VFN92wTpay26L64XnEQsfR", "GPxvxemamgNTYpe1J6J1ivr5qwsBDWFCxHHzZG67mhW3")


  "LedgerOperationManager" - {

    "when executed add nym operation" - {
      "and if ledger executes it successfully" - {
        "should return success response" taggedAs (UNSAFE_IgnoreLog)  in {
          val validResponse =
            """{"result":{"ver":"1","txn":{"metadata":{"digest":"bf6ad32c57789d763cb989f6e1d14aa8a89949fc99a07aa94cc85f2acf7cd411","reqId":1532330281707005000,"from":"Th7MpTaRZVRYnPiabds81Y"},"protocolVersion":2,"type":"1","data":{"dest":"3pB1JfL2BHNjmaUj7bbJTn","verkey":"2XwCs4gdFMKmfy23iQoFTrfewAWjnrGYmxAE7kxGR5Mz"}},"rootHash":"JBgBjiiJpD6f7Vv6VSnenCPdDvB5QWNN8Esa8vRiczzj","reqSignature":{"type":"ED25519","values":[{"value":"42XcgJQQ8sP9ibds2eEcq2yLMjUMDmnzFHxzH5ARTBjTH1sqdstydzTfUHavhPHEhNjciv67LJEupYnUAdbFrS6T","from":"Th7MpTaRZVRYnPiabds81Y"}]},"txnMetadata":{"txnId":"b4680ea46a7975006627977d624f07c9bf3cbbb3ccf3ffa92b83d8304d0dcbfc","seqNo":448,"txnTime":1532330281},"auditPath":["GfsuEw7NyYvTWhB2fnaX9jd7xkBf2sxcbmXq4hWg6w6v","H9mCzs2g2DF2AxmfxLb3VUndQEYFoVe7366TEUoukGhb","CA3gxxWgRF4SU5E5dghdLpw87P3KkT4xBYX9E22tVxnx","65kcUqw29WDPeWEe3R8eQHUDrh1dzUV2sf8AYjxypMd6","DJN2rWacgFzhgsJReD5EgSJzEFeCuU7nsvX3SdJaZSmv","7Cd531U9orb3S6sSCtMFPCZhETRyP3DR1msnz3j9LLh9","37cJ9zbeoCmn8pYm4oWaDDQ9zsWLNp4aJhheKBATAfq6","SM4p1s81TX1hLr1EMbwxPxSo3mt5SGYJkC3zsmL8WJC"]},"op":"REPLY"}""".stripMargin
          doReturn(Future(validResponse))
            .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
          when(mockWalletAPI.signLedgerRequest(any[SignLedgerRequest]))
            .thenAnswer ((i: InvocationOnMock) => Future(i.getArgument[SignLedgerRequest](0).reqDetail))
          val response = Await.result(
            ledgerTxnExecutor.addNym(submitter, targetDidPair),
            maxWaitTime
          )
          response match {
            case Right(resp) => resp shouldBe a[TxnResp]
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
            val response = Await.result(ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime)
            response match {
              case Left(resp) => resp shouldBe a[StatusDetail]
              case x => x should not be x
            }
          }
        }
      }

      "and if underlying wallet api throw an exception" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog) in {
          val response = Await.result(ledgerTxnExecutor.addNym(submitter, targetDidPair), maxWaitTime)
          response match {
            case Left(resp) => resp shouldBe a[StatusDetail]
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
          val response = Await.result(ledgerTxnExecutor.addAttrib(submitter, "VFN92wTpay26L64XnEQsfR", "url", "http:test"), maxWaitTime)
          response match {
            case Right(resp) => resp shouldBe a[TxnResp]
            case x => x should not be x
          }
        }
      }

      "and if ledger responds with no result" - {
        "should throw an exception " in {
          val validResponsesWithNullData = List(
            """{"reqId":1532348071420600000,"identifier":"Y4t7brNzourxkzHHpTSgJd","op":"REQNACK","reason":"client request invalid: CouldNotAuthenticate('Can not find verkey for Y4t7brNzourxkzHHpTSgJd',)"}"""
          )
          validResponsesWithNullData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            an [MissingReqFieldException] should be thrownBy Await.result(ledgerTxnExecutor.getNym(submitter, submitterDID), maxWaitTime)
          }
        }
      }
    }

    "when executed get nym operation" - {
      "if ledger responds with valid response" - {
        "should return success response" in {
          //TODO: add any other valid success responses in below list in which case get nym operation should still result as success
          val validResponses = List(
            """{"op":"REPLY","result":{"identifier":"PJwbFcdVdjbKVJ2q7N9dJn","seqNo":11,"state_proof":{"proof_nodes":"+QHo+LKgOflBD8eYCsTokj8CNus7zjuOnlOv\/vFoecx78Er4Pne4j\/iNuIt7ImlkZW50aWZpZXIiOiJUaDdNcFRhUlpWUlluUGlhYmRzODFZIiwicm9sZSI6bnVsbCwic2VxTm8iOjExLCJ0eG5UaW1lIjoxNTMwMTU5NTk2LCJ2ZXJrZXkiOiJEQUYydTJzZUhMODFrU1o2d0RXUnZjQmhiNFI0emtxUjJqSkVueUdnZlRWRiJ9+QExoNPSP24JsVps7QufK62cHm4MLrVBpYu1VMlThcJrixajgICgmpq6PvRB\/76zSDjdvXO+dATJAmHaV82rEVG2ZoAO+TCgbGz+V\/m\/jtA4gBROEd4I2FfuvecuUT4DAy6hoLCmtoygAhvH9MNjbLg4m1mIm3NU2MkgIFCkniHrxxesFWXTAS+gRACCCbNLp\/Y1alRAAWwlk5HNK8m01axYGilOiw+nIhmAgICAoCRyJt6FDmJQ60JcPeVA5EXTnNrRiLBPYNq9dgI6SYlCgKB9JVxOO1bVJRb8Jwgua4GPO\/Juk6XhGUySCneElMV7aqAb0qE5bnVw9F5IUz5uGMXwnAHQmog75MzPMOjuL+f3taA7Ohl8US\/Ipw9m90WNb\/7n5LAxzanxSmitjBCIzSGcGoA=","root_hash":"74LVQseYn6C3BMn8npD173jQnJbycgtqkEq7JgW7nyzM","multi_signature":{"participants":["Node1","Node3","Node4"],"signature":"QojjJnFUPdYrLWGcTxZ3DXx69dUYkEd5cRAL61i6DpnZeJyJNnw5KC6d6fuT1mo4xN9FYpwWVTGg2bFTBwrGEYuJL1RZxSrDh6nr7WF1gaSQWVyTd6qQjijWZXSAyTATikFobQ4c5cxhK41PJG9k8cAueCVPuFP4JHPpMATnSv6JRu","value":{"timestamp":1530159596,"ledger_id":1,"state_root_hash":"74LVQseYn6C3BMn8npD173jQnJbycgtqkEq7JgW7nyzM","txn_root_hash":"FEJj5PaUzUuNhZP4DkYAthDGJn2LrZZ31hzEmn91njp2","pool_state_root_hash":"n6VPkuHJYFPk6Lnzk6wFKE8HQkwiXvPXfWPb1Xw5zp4"}}},"data":"{\"dest\":\"PJwbFcdVdjbKVJ2q7N9dJn\",\"identifier\":\"Th7MpTaRZVRYnPiabds81Y\",\"role\":null,\"seqNo\":11,\"txnTime\":1530159596,\"verkey\":\"DAF2u2seHL81kSZ6wDWRvcBhb4R4zkqR2jJEnyGgfTVF\"}","reqId":1530159597037160909,"dest":"PJwbFcdVdjbKVJ2q7N9dJn","type":"105","txnTime":1530159596}}"""
          )
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getNym(submitter, submitterDID), maxWaitTime)
            response match {
              case Right(resp) => resp shouldBe a[GetNymResp]
              case x => x should not be x
            }
          }
        }
      }

      "and if ledger responds with null data" - {
        "should throw an exception" in {
          val validResponsesWithNullData = List(
            """{"result":{"data":null,"seqNo":null,"dest":"7cGUMpETdhE45Sa2A36vGb","reqId":1530159593444617377,"identifier":"PJwbFcdVdjbKVJ2q7N9dJn","type":"105","txnTime":null},"op":"REPLY"}"""
          )
          validResponsesWithNullData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getNym(submitter, submitterDID), maxWaitTime)
            response match {
              case Right(resp) =>
                resp shouldBe a[GetNymResp]
                resp.txnResp should not be(None)
                resp.nym should be(None)
                resp.verkey should be(None)
              case x => x should not be x
            }
          }
        }
      }

      "and if ledger responds with no data field" - {
        "should return success response" in {
          val validResponsesWithNoData = List(
            """{"result":{"seqNo":1,"dest":"7cGUMpETdhE45Sa2A36vGb","reqId":1530159593444617377,"identifier":"PJwbFcdVdjbKVJ2q7N9dJn","type":"105","txnTime":1},"op":"REPLY"}"""
          )
          validResponsesWithNoData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getNym(submitter, submitterDID), maxWaitTime)
            response match {
              case Right(resp) => resp shouldBe a[GetNymResp]
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
            val response = Await.result(ledgerTxnExecutor.getNym(submitter, targetDidPair.DID), maxWaitTime)
            response match {
              case Left(resp) => resp shouldBe a[StatusDetail]
              case x => x should not be x
            }
          }
        }
      }

      "and if underlying wallet api throw an exception" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog) in {
          val response = Await.result(ledgerTxnExecutor.getNym(submitter, targetDidPair.DID), maxWaitTime)
          response match {
            case Left(resp) => resp shouldBe a[StatusDetail]
            case x => x should not be x
          }
        }
      }

    }

    "when executed get schema operation" - {
      "if ledger responds with valid response" - {
        "should return success response" in {
          val validResponses = List(
            """{"op":"REPLY","result":{"txnTime":1587650567,"reqId":1587652803733668922,"state_proof":{"proof_nodes":"+QMS+JGgawq0ZaLyYyD51fhdn\/hqSPUMjOjvt2QD03oPLdSm3uuAgICgPJVOgYbuqkOYdomUuetEInSyXseLB9k2EsatgYmH8Mmgchn6HU67HM4GggG2V4mo81OAU70IE77VjUZ7gdwSieGAgICglXmtsWZRBdVkJTAbJG8Y4ZSVxrW+fD8LoNGVEk8I6qSAgICAgICA+FuNIDpsaWNlbnNlOjAuMbhL+Em4R3sibHNuIjozMCwibHV0IjoxNTg3NjUwNTY3LCJ2YWwiOnsiYXR0cl9uYW1lcyI6WyJuYW1lIiwibGljZW5zZV9udW0iXX19+HGAgKB8kyqeEwX2z4YGH3LktRSUVRMhULGnuJLIMEmL\/by1EqBcaJ\/EMjziGkzN16JLi8BRDwgSo06ppoUeiLFLKihZ0aC4Ala+Dhe81m4h4XWHW81Dmjcnb2CzjEIX93oLvv0DeICAgICAgICAgICAgPg5lxYzFYSFB6Q1FtR3duQ0dMU0c4Tjk0OjoOHhhzA6TetpAq\/S9TaIsvdt24idIVRWZYs7yNOymnlX+QFxoNPSP24JsVps7QufK62cHm4MLrVBpYu1VMlThcJrixajgICgcOGyNTg8MmRLKulTp\/tY4faplCF\/fpX+JaQIyK2N5k2gfH54M4MSyVOGwgDFszCfRHRjG6w087+60HJfkO1d2wagnMzYmX2GBla+U1ACIKO+O1EAJ7C5PWOXFlYcD1tNNXqgRACCCbNLp\/Y1alRAAWwlk5HNK8m01axYGilOiw+nIhmAoEY+o2S5z5kteHFymfqke9dK8w7\/pfihs7yAdTixJQ+JgICgJHIm3oUOYlDrQlw95UDkRdOc2tGIsE9g2r12AjpJiUKgRYruCPPWsN6Egns6vaUqdvu9d+zlqzGv9ASpzmqKx\/+gfSVcTjtW1SUW\/CcILmuBjzvybpOl4RlMkgp3hJTFe2qgHmOjVlQlG18iCeGwNuzh4PhOaIs7AVByEXyoDSHdXiOgcHTbpSoTJcRHh\/rDV+GccSApRJK3dqnhsksL6kc38zeA","multi_signature":{"participants":["Node3","Node4","Node2"],"value":{"ledger_id":1,"state_root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT","txn_root_hash":"3nW4JDuf1K9frGjR6BjobRaDC3vpRouRFKpN7CoFPJmd","timestamp":1587652803,"pool_state_root_hash":"8xwEHCkVcEA9qfBJaESpWKvtHVxUkvzJctAHfBiVhAAJ"},"signature":"QkJG2ATzZ2m3xN939LVeGBeJ8PaUzaouejR2eU8UVLb95dcPNRbvGoDRWPv4o92ave9nBWNPoTJwPab8ruYZdGTEYiigdkUqbi5Gp9mU1HFqirL29cUhVPtC4qwjQocBLqXE6edfWsptrgUmZdERgvLt7Y62ynPDc1sNq1EUEXFCqd"},"root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT"},"data":{"name":"license","attr_names":["name","license_num"],"version":"0.1"},"dest":"Pc1XHPzCQmGwnCGLSG8N94","identifier":"Th7MpTaRZVRYnPiabds81Y","seqNo":30,"type":"107"}}
pool(local):wallet1:did(Th7...81Y):indy> ledger custom {"reqId":1587652803733668922,"identifier":"Th7MpTaRZVRYnPiabds81Y","operation":{"type":"107","dest":"Pc1XHPzCQmGwnCGLSG8N94","data":{"name":"license","version":"0.1"}},"protocolVersion":2}"""
          )
          val schemaId = "TKydXSeJ72jwJbHP5weppD:2:license:0.1"
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getSchema(submitter, schemaId), maxWaitTime)
            response match {
              case Right(resp) =>
                resp.schema.get.name shouldBe "license"
                resp.schema.get.version shouldBe "0.1"
              case x => x should not be x
            }
          }
        }
        "and if ledger responds with null data" in {
          val validResponses = List(
            """{"op":"REPLY","result":{"txnTime":null,"reqId":1587652803733668922,"state_proof":{"proof_nodes":"+QMS+JGgawq0ZaLyYyD51fhdn\/hqSPUMjOjvt2QD03oPLdSm3uuAgICgPJVOgYbuqkOYdomUuetEInSyXseLB9k2EsatgYmH8Mmgchn6HU67HM4GggG2V4mo81OAU70IE77VjUZ7gdwSieGAgICglXmtsWZRBdVkJTAbJG8Y4ZSVxrW+fD8LoNGVEk8I6qSAgICAgICA+FuNIDpsaWNlbnNlOjAuMbhL+Em4R3sibHNuIjozMCwibHV0IjoxNTg3NjUwNTY3LCJ2YWwiOnsiYXR0cl9uYW1lcyI6WyJuYW1lIiwibGljZW5zZV9udW0iXX19+HGAgKB8kyqeEwX2z4YGH3LktRSUVRMhULGnuJLIMEmL\/by1EqBcaJ\/EMjziGkzN16JLi8BRDwgSo06ppoUeiLFLKihZ0aC4Ala+Dhe81m4h4XWHW81Dmjcnb2CzjEIX93oLvv0DeICAgICAgICAgICAgPg5lxYzFYSFB6Q1FtR3duQ0dMU0c4Tjk0OjoOHhhzA6TetpAq\/S9TaIsvdt24idIVRWZYs7yNOymnlX+QFxoNPSP24JsVps7QufK62cHm4MLrVBpYu1VMlThcJrixajgICgcOGyNTg8MmRLKulTp\/tY4faplCF\/fpX+JaQIyK2N5k2gfH54M4MSyVOGwgDFszCfRHRjG6w087+60HJfkO1d2wagnMzYmX2GBla+U1ACIKO+O1EAJ7C5PWOXFlYcD1tNNXqgRACCCbNLp\/Y1alRAAWwlk5HNK8m01axYGilOiw+nIhmAoEY+o2S5z5kteHFymfqke9dK8w7\/pfihs7yAdTixJQ+JgICgJHIm3oUOYlDrQlw95UDkRdOc2tGIsE9g2r12AjpJiUKgRYruCPPWsN6Egns6vaUqdvu9d+zlqzGv9ASpzmqKx\/+gfSVcTjtW1SUW\/CcILmuBjzvybpOl4RlMkgp3hJTFe2qgHmOjVlQlG18iCeGwNuzh4PhOaIs7AVByEXyoDSHdXiOgcHTbpSoTJcRHh\/rDV+GccSApRJK3dqnhsksL6kc38zeA","multi_signature":{"participants":["Node4","Node2","Node1"],"value":{"ledger_id":1,"state_root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT","txn_root_hash":"3nW4JDuf1K9frGjR6BjobRaDC3vpRouRFKpN7CoFPJmd","timestamp":1587663940,"pool_state_root_hash":"8xwEHCkVcEA9qfBJaESpWKvtHVxUkvzJctAHfBiVhAAJ"},"signature":"RE82H8LXwkbxaGBgj1fYBoJs6VyKBxonZCmxv12iDBFSaohJbJ1VV3gMdjn5ssart6RU1u3QfqJc6MHVjt7Mn7FDzuUP1eqo1Bf5tayyUx7egp4fMQaWhKTkpr9wwTqiDWirJz8vQzyX3pVkkbTGfmy4ZAqdkrpwrz3QGZnAsFwq9Z"},"root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT"},"data":{"name":"license","version":"0.2"},"dest":"Pc1XHPzCQmGwnCGLSG8N94","identifier":"Th7MpTaRZVRYnPiabds81Y","seqNo":null,"type":"107"}}"""
          )
          val schemaId = "TKydXSeJ72jwJbHP5weppD:2:license:0.2"
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getSchema(submitter, schemaId), maxWaitTime)
            response match {
              case Right(resp) =>
                resp.schema shouldBe None
              case x => x should not be x
            }
          }
        }
      }
    }

    "when executed get cred def operation" - {
      "if ledger responds with valid response" - {
        "should return success response" in {
          val validResponses = List(
            """{"op":"REPLY","result":{"data":{"primary":{"n":"1041927","s":"968659808","z":"6892533933824119","rctxt":"22828","r":{"master_secret":"342234","name":"20778161110","license_num":"76072"}}},"identifier":"Th7MpTaRZVRYnPiabds81Y","reqId":1587674031402465014,"origin":"Pc1XHPzCQmGwnCGLSG8N94","signature_type":"CL","txnTime":1587650590,"type":"108","ref":30,"seqNo":31,"state_proof":{"multi_signature":{"participants":["Node3","Node4","Node1"],"value":{"pool_state_root_hash":"8xwEHCkVcEA9qfBJaESpWKvtHVxUkvzJctAHfBiVhAAJ","timestamp":1587673873,"state_root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT","ledger_id":1,"txn_root_hash":"3nW4JDuf1K9frGjR6BjobRaDC3vpRouRFKpN7CoFPJmd"},"signature":"R96YLkm3hZQcW"},"proof_nodes":"+RSm6ogTpDTDoz","root_hash":"EQkVztavV2UnQ"},"tag":"tag"}}"""
          )
          val schemaId = "TKydXSeJ72jwJbHP5weppD:3:CL:31:tag"
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getCredDef(submitter, schemaId), maxWaitTime)
            response match {
              case Right(resp) =>
                resp.credDef should not be None
                resp.credDef.get.schemaId shouldBe "30"
                resp.credDef.get.id shouldBe "Pc1XHPzCQmGwnCGLSG8N94:3:CL:30:tag"
              case x => x should not be x
            }
          }
        }
        "and if ledger responds with null data" in {
          val validResponses = List(
            """{"op":"REPLY","result":{"txnTime":null,"reqId":1587674031402465014,"data":null,"signature_type":"CL","identifier":"Th7MpTaRZVRYnPiabds81Y","tag":"tag","type":"108","state_proof":{"proof_nodes":"+QJC+JGgawq0ZaLyYyD51fhdn\/hqSPUMjOjvt2QD03oPLdSm3uuAgICgPJVOgYbuqkOYdomUuetEInSyXseLB9k2EsatgYmH8Mmgchn6HU67HM4GggG2V4mo81OAU70IE77VjUZ7gdwSieGAgICglXmtsWZRBdVkJTAbJG8Y4ZSVxrW+fD8LoNGVEk8I6qSAgICAgICA+DmXFjMVhIUHpDUW1Hd25DR0xTRzhOOTQ6Og4eGHMDpN62kCr9L1Noiy923biJ0hVFZlizvI07KaeVf5AXGg09I\/bgmxWmztC58rrZwebgwutUGli7VUyVOFwmuLFqOAgKBw4bI1ODwyZEsq6VOn+1jh9qmUIX9+lf4lpAjIrY3mTaB8fngzgxLJU4bCAMWzMJ9EdGMbrDTzv7rQcl+Q7V3bBqCczNiZfYYGVr5TUAIgo747UQAnsLk9Y5cWVhwPW001eqBEAIIJs0un9jVqVEABbCWTkc0rybTVrFgaKU6LD6ciGYCgRj6jZLnPmS14cXKZ+qR710rzDv+l+KGzvIB1OLElD4mAgKAkcibehQ5iUOtCXD3lQORF05za0YiwT2DavXYCOkmJQqBFiu4I89aw3oSCezq9pSp2+7137OWrMa\/0BKnOaorH\/6B9JVxOO1bVJRb8Jwgua4GPO\/Juk6XhGUySCneElMV7aqAeY6NWVCUbXyIJ4bA27OHg+E5oizsBUHIRfKgNId1eI6BwdNulKhMlxEeH+sNX4ZxxIClEkrd2qeGySwvqRzfzN4A=","multi_signature":{"participants":["Node3","Node4","Node1"],"value":{"ledger_id":1,"state_root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT","txn_root_hash":"3nW4JDuf1K9frGjR6BjobRaDC3vpRouRFKpN7CoFPJmd","timestamp":1587673873,"pool_state_root_hash":"8xwEHCkVcEA9qfBJaESpWKvtHVxUkvzJctAHfBiVhAAJ"},"signature":"R96YLkm3hZQcW2P6q2ifBYKKJdzXgD9WKh4EgfjA6VujztVNjvRZFE5iZuzNXLYysYLCwvtDuLXJWJxfqhhfmexe7BgYxRPfK3At2VigFkGuWVeJ6bf1SodRsBPmBFKSffBF6P5fpSpqvXKB1w6jf3mzaJDhb8pABAuC7YJJgHrPzw"},"root_hash":"EQkVztavV2UnQkiX3NyJ3UG2i5yVdbrgh58rrRZLP5DT"},"origin":"Pd1XHPzCQmGwnCGLSG8N94","ref":30,"seqNo":null}}"""
          )
          val schemaId = "TKydXSeJ72jwJbHP5weppD:3:CL:31:tag"
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getCredDef(submitter, schemaId), maxWaitTime)
            response match {
              case Right(resp) =>
                resp.credDef shouldBe None
              case x => x should not be x
            }
          }
        }
      }
    }

    "when executed get attrib operation" - {
      "and if ledger respond valid json" - {
        "should return success response" in {
          //TODO: add any other valid success responses in below list in which case get nym operation should still result as success
          val validResponses = List(
            """{"result":{"raw":"url","txnTime":1530159597,"type":"104","state_proof":{"root_hash":"71V5ctaQKz9CHH9zhti2eWSGLMBKcwviY1gKE3enxMmc","multi_signature":{"value":{"ledger_id":1,"state_root_hash":"71V5ctaQKz9CHH9zhti2eWSGLMBKcwviY1gKE3enxMmc","txn_root_hash":"7DCA8vGLifZogvvEuUgdv3TsPGo2a5d6Xr2GubpuwS2p","pool_state_root_hash":"n6VPkuHJYFPk6Lnzk6wFKE8HQkwiXvPXfWPb1Xw5zp4","timestamp":1530159597},"participants":["Node1","Node4","Node2"],"signature":"R9PwZn5pwcVC7ZfrddL3wT4XddmRztnqPb7WgHQRKVQf78tiwxp6JriH5gRoEZE9WKmz4gAkCooPiyoVVqnFFqfDh57JiLdxDu2HKiRnKeEF3EDb42pcEJ1ADgoyfgZyHobRnGizg2gUCDvVfSoyi9Sv4P9ALETAChma1CPTv36rxX"},"proof_nodes":"+QJO+FGg\/qxG7GonMK2fDCCRa0Ea+TzYE8UyzhdADO0+RbGxBSGAgICAgICAgKCVea2xZlEF1WQlMBskbxjhlJXGtb58Pwug0ZUSTwjqpICAgICAgID4xbhZIEp3YkZjZFZkamJLVkoycTdOOWRKbjoxOjI4ZTVlYmFiZDlkOGY2ZTIzN2RmNjNkYTJiNTAzNzg1MDkzZjAyMjkyNDFiYzcwMjExOThmNjNjNDNiOTMyNjm4aPhmuGR7ImxzbiI6MTIsImx1dCI6MTUzMDE1OTU5NywidmFsIjoiNGEzMmQ2MzE1ZDk3MDE5ZjQyZWY5Zjc1ODU4ZjQ2OTdiZTNmNDNhNWNkOTQxZDE3YzUxZTVjN2Y0MjljMWU5MiJ9+QExoNPSP24JsVps7QufK62cHm4MLrVBpYu1VMlThcJrixajgICgmpq6PvRB\/76zSDjdvXO+dATJAmHaV82rEVG2ZoAO+TCgbGz+V\/m\/jtA4gBROEd4I2FfuvecuUT4DAy6hoLCmtoygSUhBoXAjOnzEhJU2n0\/wnIVra9syrqYk8zEwb+6vZAmgRACCCbNLp\/Y1alRAAWwlk5HNK8m01axYGilOiw+nIhmAgICAoCRyJt6FDmJQ60JcPeVA5EXTnNrRiLBPYNq9dgI6SYlCgKB9JVxOO1bVJRb8Jwgua4GPO\/Juk6XhGUySCneElMV7aqAb0qE5bnVw9F5IUz5uGMXwnAHQmog75MzPMOjuL+f3taA7Ohl8US\/Ipw9m90WNb\/7n5LAxzanxSmitjBCIzSGcGoA="},"data":"{\"url\":\"testvalue\"}","dest":"PJwbFcdVdjbKVJ2q7N9dJn","seqNo":12,"reqId":1530159597309824163,"identifier":"PJwbFcdVdjbKVJ2q7N9dJn"},"op":"REPLY"}"""
          )
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.result(ledgerTxnExecutor.getAttrib(submitter, "4ZhauNeFr1Sv8qtGBvjjxH", "url"), maxWaitTime)
            response match {
              case Right(resp) => resp shouldBe a[GetAttribResp]
              case x => x should not be x
            }
          }
        }
      }

      "and if ledger responds with null data" - {
        "should throw an exception" in {
          val responsesWithNullData = List(
            """{"result":{"seqNo":null,"dest":"PJwbFcdVdjbKVJ2q7N9dJn","data":null,"type":"104","raw":"url","txnTime":null,"identifier":"PJwbFcdVdjbKVJ2q7N9dJn","state_proof":{"root_hash":"74LVQseYn6C3BMn8npD173jQnJbycgtqkEq7JgW7nyzM","multi_signature":{"signature":"QojjJnFUPdYrLWGcTxZ3DXx69dUYkEd5cRAL61i6DpnZeJyJNnw5KC6d6fuT1mo4xN9FYpwWVTGg2bFTBwrGEYuJL1RZxSrDh6nr7WF1gaSQWVyTd6qQjijWZXSAyTATikFobQ4c5cxhK41PJG9k8cAueCVPuFP4JHPpMATnSv6JRu","value":{"timestamp":1530159596,"state_root_hash":"74LVQseYn6C3BMn8npD173jQnJbycgtqkEq7JgW7nyzM","txn_root_hash":"FEJj5PaUzUuNhZP4DkYAthDGJn2LrZZ31hzEmn91njp2","ledger_id":1,"pool_state_root_hash":"n6VPkuHJYFPk6Lnzk6wFKE8HQkwiXvPXfWPb1Xw5zp4"},"participants":["Node4","Node3","Node1"]},"proof_nodes":"+QG3+IGgOb3tL9rLCaNhaLueITfpAXOXuPKY3xpts7IFO2f\/3IK4XvhcuFp7ImlkZW50aWZpZXIiOm51bGwsInJvbGUiOiIwIiwic2VxTm8iOjEsInR4blRpbWUiOm51bGwsInZlcmtleSI6In5Db1JFUjYzRFZZbldadEs4dUF6TmJ4In35ATGg09I\/bgmxWmztC58rrZwebgwutUGli7VUyVOFwmuLFqOAgKCamro+9EH\/vrNION29c750BMkCYdpXzasRUbZmgA75MKBsbP5X+b+O0DiAFE4R3gjYV+695y5RPgMDLqGgsKa2jKACG8f0w2NsuDibWYibc1TYySAgUKSeIevHF6wVZdMBL6BEAIIJs0un9jVqVEABbCWTkc0rybTVrFgaKU6LD6ciGYCAgICgJHIm3oUOYlDrQlw95UDkRdOc2tGIsE9g2r12AjpJiUKAoH0lXE47VtUlFvwnCC5rgY878m6TpeEZTJIKd4SUxXtqoBvSoTludXD0XkhTPm4YxfCcAdCaiDvkzM8w6O4v5\/e1oDs6GXxRL8inD2b3RY1v\/ufksDHNqfFKaK2MEIjNIZwagA=="},"reqId":1530159597137652617},"op":"REPLY"}"""
          )
          responsesWithNullData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            an [InvalidValueException]  should be thrownBy  Await.result(ledgerTxnExecutor.getAttrib(submitter, "4ZhauNeFr1Sv8qtGBvjjxH", "url"), maxWaitTime)
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
            val response = Await.result(ledgerTxnExecutor.getTAA(submitter), maxWaitTime)
            response match {
              case Right(resp) => resp shouldBe a[GetTAAResp]
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
            val response = Await.result(ledgerTxnExecutor.getTAA(submitter), maxWaitTime)
            response match {
              case Left(status) => status shouldBe TAA_NOT_SET_ON_THE_LEDGER
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
            val response = Await.result(ledgerTxnExecutor.getTAA(submitter), maxWaitTime)
            response match {
              case Left(status) => status shouldBe TAA_NOT_SET_ON_THE_LEDGER
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
            val response = Await.result(ledgerTxnExecutor.getTAA(submitter), maxWaitTime)
            response match {
              case Left(resp) => resp shouldBe a[StatusDetail]
              case x => x should not be x
            }
          }
        }
      }

      "and if underlying wallet api throw an exception" - {
        "should return error response" taggedAs (UNSAFE_IgnoreLog)  in {
          val response = Await.result(ledgerTxnExecutor.getTAA(submitter), maxWaitTime)
          response match {
            case Left(resp) => resp shouldBe a[StatusDetail]
            case x => x should not be x
          }
        }
      }
    }
  }

}
