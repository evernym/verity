package com.evernym.verity.vdrtools

import com.evernym.verity.util2.Status.{StatusDetail, StatusDetailException}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.wallet.SignLedgerRequest
import com.evernym.verity.ledger._
import com.evernym.verity.vdrtools.ledger.{IndyLedgerPoolConnManager, LedgerTxnExecutorV1, SubmitToLedger}
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.vdrtools.ErrorCode.PoolLedgerTimeout
import com.evernym.vdrtools.IndyException
import com.evernym.vdrtools.pool.Pool
import com.evernym.verity.util2.Exceptions.InvalidValueException
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

    "when executed get attrib operation" - {
      "and if ledger respond valid json" - {
        "should return success response" in {
          //TODO: add any other valid success responses in below list in which case get nym operation should still result as success
          val validResponses = List(
            """{"op":"REPLY","result":{"type":"104","raw":"url","state_proof":{"multi_signature":{"participants":["Node3","Node2","Node4"],"signature":"RJUgZLnZMxwf7ozsefFmYDm795PKFahYgYr8QvvHKfpEGBZpwr56txLjL7EZLD7cRhbhb4qXE1A9j26ogYSiQsritc9ibEUFiLnwxaVDGR5tBe7GxwAtuURoxS1k73SGLbTnaiyVKeR34jBo1MUuboxEyVL8DXP2TcC4fMBvfKdNQP","value":{"ledger_id":1,"timestamp":1530159820,"state_root_hash":"7DziE7hs7iE6kxG4Wt5kr2qyZimS7zgCN17ZDDRpoaEF","txn_root_hash":"3QqkXDa3T35oDUtKiX9nfYZuJLzGXxcA7Bqyt2Hb4nJd","pool_state_root_hash":"DuhjUiR6QDsT4X3KFTGHgPnaCCTTVMhmmA8uRwkkhDwA"}},"root_hash":"7DziE7hs7iE6kxG4Wt5kr2qyZimS7zgCN17ZDDRpoaEF","proof_nodes":"+QWW+JGAgICAoGzbX0QvmZCsFyiWSK++M0+G0D9FbGDAUzgxQYbCzJOUoGIu7WSMnmfXL3jS4WpyvIl3kDM\/wsbjBKCqR5rn2nMVoNGaMD5xSATpIFdihsl995yCeRe6QxiKXSw964aohlu1gICAgKC1pLZif5\/DfCMSqk+otSLPNObQ0wnu3i6e8gGVNDKG1oCAgICA+FGAgKAbwetCibkG\/ApzMjESeSTTdr0ACirQvPuKTpLA\/z6VbICAgICAgKChOCAYmzeBJHVc9xxtxaANXFt0l+xJtjtbGhPi7JXjlICAgICAgID4xrhYIEJBeE5ZdmpSMUQ5YUw2R01yM3ZLOgE6MjhlNWViYWJkOWQ4ZjZlMjM3ZGY2M2RhMmI1MDM3ODUwOTNmMDIyOTI0MWJjNzAyMTE5OGY2M2M0M2I5MzI2Obhq+Gi4ZnsibHNuIjozNTg5LCJsdXQiOjE1MzAxNTk4MjAsInZhbCI6IjRhMzJkNjMxNWQ5NzAxOWY0MmVmOWY3NTg1OGY0Njk3YmUzZjQzYTVjZDk0MWQxN2M1MWU1YzdmNDI5YzFlOTIiffkB0aAwRQizsRuYn6bEcLuSx7lnM9mJHpYnJYY+\/p7qA\/SpoKDlF4a9gclzrs3nQ2bW4\/ApUqhSupSv7o0eFinTGx0uDaAmmki+Nnh+49rtLV0zFBZuGtzmAZJ8L+KrXQp0SU6+XqDwmK8rOYSrqGdILuaEj5H8IutoRkw5UDQq51kApdih76Cz9+4dmBD0o9yACRv8\/7zKSe2QL7kLgEhsXvjiVkJDRqBJUakcYjWtRpS0d4xYS\/+tK6UWaiPBxAlq6CjvXIw1e6ARY6GGUPZLYWpnS\/Kk4BtjePOf+PZnK+Tw7rWjzFE7u6CGEytXp2OkOhV3ZRe9cmyxeWenxwI3wf6o7UBNiyH\/DaDkhmMgXt4ViomPQNFQnc1cBEfIUo9p05a1oIErauZI76C0Z5jrri+F3X1rzmIFGwjGJ0uOlFaGFCgxPHgrxp\/staD5Zmu\/wTjsFGxNbrtiRRKDsdLx8W+ZBgZgDNgVCS3+eaCu\/UQ7QDi\/\/esVohTitriggUImszDHNjqOKpXcHrzJ0KAwqLGmNgs6MbHA578g\/Qp6g6mKImYcPhHEsYCQUFuqPYCgpz2jQd9TpCq6bBP+upfWxhDTdSn4fhHhobDXhfmFyjGAgPkCEaDw0SNcNhxO\/EUhawUH5XBgqy+uhsIQysV5slCyVFuN1aBhsWIeWox04RzorOnYR2jUePiQ2nf8nLQGezv74wx8G6Am\/wvkIhbUubrm35VuqQk\/vsAjnT3LEuB6NrMVAMZ2o6AyRopPjX1lJSy5naMUvjnYR9OaEYWYKAY1mvs8Hg1vMqDcz1zdZCDT9lkU6Qx27SEKH\/\/\/DtCEMehgULmtC9plPaBwBL6mvT2ixJpZOfYAinOJCUvdUbxi1OQTYNioSZqPT6Ag99lVbjpTTilBsq7m6Yc+7CF8w1b7hdG8JvGOv1lr36Dyyv\/dRGwE8wZBpCkYs0MwM\/Pq3f0\/zyvtCjUPbMJzn6DcA9BJjKjyUatLy6x5Y575VRAjMy5gHm95rrFeJvjOVaD0+FYZo770Vd5qNOf9xkrYDQ50lkdEQZKnu1uDbG\/4waAqTkvBGPmhhRtQ9ZWTAHMgzKmYd5Gd6ksPN3IVRBi0eaAUDHytnrirbnSjuV\/b7SE480eoAi0iONN7jhDKU2pTqKDYU8aR+zAmw06wRh4O4qe8NGdesZc+q\/4RcL8Ohv9wEKC+yRY4B\/qI+oSvMZgNbIZYDWPN2VF6J5lVtI8mcXyZgqBqaJiTAUoCXhL10pgsjv3QF6ugh4J+gCmlMCVwNIUNoaCrTPIxEEEMOY5HPfgTy2QXWrolBm7x5MpcFPgJGHO54IA="},"dest":"YYBAxNYvjR1D9aL6GMr3vK","data":"{\"url\":\"testvalue\"}","reqId":1530159820322097999,"txnTime":1530159820,"identifier":"YYBAxNYvjR1D9aL6GMr3vK","seqNo":3589}}"""
          )
          validResponses.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            val response = Await.ready(ledgerTxnExecutor.getAttrib(submitter, "4ZhauNeFr1Sv8qtGBvjjxH", "url"), maxWaitTime).value.get
            response match {
              case Success(resp) => resp shouldBe a[GetAttribResp]
              case x => x should not be x
            }
          }
        }
      }

      "and if ledger responds with null data" - {
        "should throw an exception" in {
          val responsesWithNullData = List(
            """{"result":{"reqId":1530159819419371330,"data":null,"identifier":"YYBAxNYvjR1D9aL6GMr3vK","dest":"YYBAxNYvjR1D9aL6GMr3vK","state_proof":{"proof_nodes":"+QVD+QHRoDBFCLOxG5ifpsRwu5LHuWcz2Ykeliclhj7+nuoD9KmgoOUXhr2ByXOuzedDZtbj8ClSqFK6lK\/ujR4WKdMbHS4NoCaaSL42eH7j2u0tXTMUFm4a3OYBknwv4qtdCnRJTr5eoPCYrys5hKuoZ0gu5oSPkfwi62hGTDlQNCrnWQCl2KHvoLP37h2YEPSj3IAJG\/z\/vMpJ7ZAvuQuASGxe+OJWQkNGoElRqRxiNa1GlLR3jFhL\/60rpRZqI8HECWroKO9cjDV7oBFjoYZQ9kthamdL8qTgG2N485\/49mcr5PDutaPMUTu7oIYTK1enY6Q6FXdlF71ybLF5Z6fHAjfB\/qjtQE2LIf8NoOSGYyBe3hWKiY9A0VCdzVwER8hSj2nTlrWggStq5kjvoEJq9L04z+CeA26hSjxXtEeUQKJHUrjwCBgZSo7lfba9oPlma7\/BOOwUbE1uu2JFEoOx0vHxb5kGBmAM2BUJLf55oK79RDtAOL\/96xWiFOK2uKCBQiazMMc2Oo4qldwevMnQoDCosaY2CzoxscDnvyD9CnqDqYoiZhw+EcSxgJBQW6o9gKCnPaNB31OkKrpsE\/66l9bGENN1Kfh+EeGhsNeF+YXKMYCA+Ma4WDJ1VkNja1k2dmZaZlg5a2NRWmUzdToBOjI4ZTVlYmFiZDlkOGY2ZTIzN2RmNjNkYTJiNTAzNzg1MDkzZjAyMjkyNDFiYzcwMjExOThmNjNjNDNiOTMyNjm4avhouGZ7ImxzbiI6MzQ1MSwibHV0IjoxNTI5NDk4MDMwLCJ2YWwiOiJiNDdhN2MyNDliOTAyZmVmNmI0NGRmOGUxOGJjMmFmYTJiOGUwZDNmMTdkMzI0MDJhOWE4YjRmODE0Mjk4YzdhIn34kYCAgICgbNtfRC+ZkKwXKJZIr74zT4bQP0VsYMBTODFBhsLMk5SgTa6YnQUdEplDisb3OSmukpIbdWvxicLGDXQf9yXZt+Og0ZowPnFIBOkgV2KGyX33nIJ5F7pDGIpdLD3rhqiGW7WAgICAoLWktmJ\/n8N8IxKqT6i1Is805tDTCe7eLp7yAZU0MobWgICAgID5AhGg8NEjXDYcTvxFIWsFB+VwYKsvrobCEMrFebJQslRbjdWgYbFiHlqMdOEc6Kzp2Edo1Hj4kNp3\/Jy0Bns7++MMfBugJv8L5CIW1Lm65t+VbqkJP77AI509yxLgejazFQDGdqOgMkaKT419ZSUsuZ2jFL452EfTmhGFmCgGNZr7PB4NbzKg3M9c3WQg0\/ZZFOkMdu0hCh\/\/\/w7QhDHoYFC5rQvaZT2gAJSjvCAlTwgzUkRd2aMZRihu0Uiop5EtvadaOIf7faegIPfZVW46U04pQbKu5umHPuwhfMNW+4XRvCbxjr9Za9+g8sr\/3URsBPMGQaQpGLNDMDPz6t39P88r7Qo1D2zCc5+g3APQSYyo8lGrS8useWOe+VUQIzMuYB5vea6xXib4zlWg9PhWGaO+9FXeajTn\/cZK2A0OdJZHREGSp7tbg2xv+MGgKk5LwRj5oYUbUPWVkwBzIMypmHeRnepLDzdyFUQYtHmgFAx8rZ64q250o7lf2+0hOPNHqAItIjjTe44QylNqU6ig2FPGkfswJsNOsEYeDuKnvDRnXrGXPqv+EXC\/Dob\/cBCgvskWOAf6iPqErzGYDWyGWA1jzdlReieZVbSPJnF8mYKgamiYkwFKAl4S9dKYLI790BeroIeCfoAppTAlcDSFDaGgq0zyMRBBDDmORz34E8tkF1q6JQZu8eTKXBT4CRhzueCA","root_hash":"EDVV5DoKr8fLQU93SV7t1M6nEcqBLWz4j3iyG4fsYAgm","multi_signature":{"value":{"pool_state_root_hash":"DuhjUiR6QDsT4X3KFTGHgPnaCCTTVMhmmA8uRwkkhDwA","ledger_id":1,"state_root_hash":"EDVV5DoKr8fLQU93SV7t1M6nEcqBLWz4j3iyG4fsYAgm","txn_root_hash":"5pkD8KKJ5MpbH6uFuc139gFYJFyN97QN9a43yuFv9w3Y","timestamp":1530159818},"participants":["Node4","Node2","Node3"],"signature":"QmJEcsYnppfw5Mg32kKtakjfaV67xSYc8SJWV9ZSkeCZH1bQ4sX24V2ks7fZsCuiUPqLALA6zbc1o4tBBuKaX7zhbh8tXSmb6rRYJ1MrPBX1hy4TE4tf9eQQidnrzqnfjkAwT1LVcvudQUYV5Jh6abtV95rKquyrJysKLxxTfdTUcw"}},"type":"104","txnTime":null,"raw":"url","seqNo":null},"op":"REPLY"}"""
          )
          responsesWithNullData.foreach { vr =>
            doReturn(Future(vr))
              .when(mockLedgerSubmitAPI).submitRequest(any[Pool], any[String])
            an [InvalidValueException]  should be thrownBy  Await.result(ledgerTxnExecutor.getAttrib(submitter, "4ZhauNeFr1Sv8qtGBvjjxH", "url"), maxWaitTime)
          }
        }
      }
    }
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}
