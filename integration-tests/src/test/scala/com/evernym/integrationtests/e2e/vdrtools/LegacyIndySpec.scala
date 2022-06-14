package com.evernym.integrationtests.e2e.vdrtools

import akka.actor.ActorSystem
import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider
import com.evernym.vdrtools.anoncreds.Anoncreds
import com.evernym.vdrtools.ledger.Ledger
import com.evernym.vdrtools.ledger.Ledger.{buildGetTxnAuthorAgreementRequest, buildSchemaRequest}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, SignLedgerRequest}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigUtil.findTAAConfig
import com.evernym.verity.ledger.{GetTAAResp, LedgerRequest, LedgerTAA, OpenConnException, Submitter, TransactionAuthorAgreement, TxnResp}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerRejectException
import com.evernym.verity.testkit.util.{LedgerUtil => LegacyLedgerUtil}
import com.evernym.verity.testkit.{BasicSpec, LedgerClient, TestWallet}
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.util.Util.getMapWithAnyValueFromJsonString
import com.evernym.verity.util2.Status.{StatusDetailException, TAA_INVALID_JSON, TAA_NOT_SET_ON_THE_LEDGER}
import com.evernym.verity.vdr.{FqDID, VDRUtil}
import com.evernym.verity.vdrtools.Libraries
import com.evernym.verity.vdrtools.ledger.{IndyLedgerPoolConnManager, LedgerTxnUtil, V2TxnRespBuilder}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}

import java.time.{Instant, LocalDate}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.compat.java8.FutureConverters.{toScala => toFuture}


class LegacyIndySpec
  extends BasicSpec
    with BeforeAndAfterAll
    with Eventually {

  override def beforeAll(): Unit = {
    Libraries.initialize(appConfig)
  }

  implicit val system: ActorSystem = ActorSystemVanilla("test", baseConfig)
  implicit val ec: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext

  var trusteeWallet = new TestWallet(ec, createWallet = true, system)
  var trusteeKey: NewKeyCreated = trusteeWallet.executeSync[NewKeyCreated](CreateNewKey(seed = Option("000000000000000000000000Trustee1")))
  var trusteeFqDid: FqDID = VDRUtil.toFqDID(trusteeKey.did, UNQUALIFIED_LEDGER_PREFIX, legacyLedgerPrefixMapping)

  var issuerWallet = new TestWallet(ec, createWallet = true, system)
  var issuerKey: NewKeyCreated = issuerWallet.executeSync[NewKeyCreated](CreateNewKey())
  var issuerFqDid: FqDID = VDRUtil.toFqDID(issuerKey.did, UNQUALIFIED_LEDGER_PREFIX, legacyLedgerPrefixMapping)
  var issuerSubmitter: Submitter = Submitter(issuerKey.did, Some(issuerWallet.wap))

  val holderWallet = new TestWallet(ec, createWallet = true, system)
  var holderKey: NewKeyCreated = holderWallet.executeSync[NewKeyCreated](CreateNewKey())
  var holderFqDid: FqDID = VDRUtil.toFqDID(holderKey.did, UNQUALIFIED_LEDGER_PREFIX, legacyLedgerPrefixMapping)

  val legacyLedgerUtil: LegacyLedgerUtil = LedgerClient.buildLedgerUtil(
    config = new TestAppConfig(newConfig = Option(baseConfig), clearValidators = true),
    ec = ec,
    submitterDID = Option(trusteeKey.did),
    submitterKeySeed = Option("000000000000000000000000Trustee1"),
    genesisTxnPath = Option("target/genesis.txt")
  )

  val poolConnManager: IndyLedgerPoolConnManager = new IndyLedgerPoolConnManager(system, appConfig, ec)
  poolConnManager.open()

  "LegacyIndyLedgerApi" - {

    "when tried to write schema before issuer DID is on the ledger" - {
      "should fail" in {
        val ex = intercept[LedgerRejectException] {
          val schemaCreated = Anoncreds.issuerCreateSchema(
            issuerKey.did,
            "employment",
            "1.0",
            seqToJson(List("name", "company"))
          ).get()

          val schemaReq = runAsSync(toFuture(buildSchemaRequest(issuerKey.did, schemaCreated.getSchemaJson)))
          executeWriteLedgerReq(schemaReq, getTAA)
        }
        ex.getMessage shouldBe s"client request invalid: could not authenticate, verkey for ${issuerKey.did} cannot be found"
      }
    }

    "when tried to bootstrap issuer DID" - {
      "should be successful" in {
        legacyLedgerUtil.bootstrapNewDID(issuerKey.did, issuerKey.verKey, "ENDORSER")
        eventually(Timeout(10.seconds), Interval(Duration("20 seconds"))) {
          legacyLedgerUtil.checkDidOnLedger(issuerKey.did, issuerKey.verKey, "ENDORSER")
        }
      }
    }

    "when tried to write schema" - {
      "should be successful" in {
        val response = {
          val schemaCreated = Anoncreds.issuerCreateSchema(
            issuerKey.did,
            "employment",
            "1.0",
            seqToJson(List("name", "company"))
          ).get()

          val schemaReq = runAsSync(toFuture(buildSchemaRequest(issuerKey.did, schemaCreated.getSchemaJson)))
          executeWriteLedgerReq(schemaReq, getTAA)
        }
      }
    }
  }

  private def getTAA: Option[TransactionAuthorAgreement] = {
    val getTAAReq = runAsSync(toFuture(buildGetTxnAuthorAgreementRequest(issuerSubmitter.did, null)))
    val txnResp = executeReadLedgerReq(getTAAReq, None)
    val data = txnResp.data.flatMap{ m =>
      try {
        val version = m.get("version").map(_.asInstanceOf[String])
        val text = m.get("text").map(_.asInstanceOf[String])
        version.zip(text)
      } catch {
        case _: ClassCastException => None
      }
    }.map(x => LedgerTAA(x._1, x._2))

    val getTAAResp = data match {
      case Some(d) => GetTAAResp(txnResp, d)
      case None =>
        if(txnResp.data.isEmpty) throw StatusDetailException(TAA_NOT_SET_ON_THE_LEDGER)
        else throw StatusDetailException(TAA_INVALID_JSON)
    }
    val ledgerTAA = getTAAResp.taa
    val expectedDigest = HashUtil.hash(SHA256)(ledgerTAA.version + ledgerTAA.text).hex
    val configuredTaa = findTAAConfig(appConfig, ledgerTAA.version)
    configuredTaa match {
      case Some(taa) =>
        if (expectedDigest.toLowerCase() != taa.digest.toLowerCase()) {
          throw OpenConnException("Configured TAA Digest doesn't match ledger TAA")
        } else {
          configuredTaa
        }
      case None =>
        throw OpenConnException("TAA is not configured")
    }
  }

  private def executeWriteLedgerReq(req: String, taa: Option[TransactionAuthorAgreement]): TxnResp = {
    val resp = executeLedgerReq(req, taa)
    V2TxnRespBuilder.buildTxnRespForWriteOp(getMapWithAnyValueFromJsonString(resp))
  }

  private def executeReadLedgerReq(req: String, taa: Option[TransactionAuthorAgreement]): TxnResp = {
    val resp = executeLedgerReq(req, taa)
    V2TxnRespBuilder.buildTxnRespForReadOp(getMapWithAnyValueFromJsonString(resp))
  }

  private def executeLedgerReq(req: String, taa: Option[TransactionAuthorAgreement]): String = {
    val ledgerReq = LedgerRequest(req, needsSigning = false, taa)
    val taaLedgerReq = runAsSync(LedgerTxnUtil.appendTAAToRequest(ledgerReq, taa))
    val signedLedgerReq = issuerWallet.executeSync[LedgerRequest](SignLedgerRequest(taaLedgerReq, issuerSubmitter))
    val finalLedgerReq = taaLedgerReq.prepared(signedLedgerReq.req)
    runAsSync(toFuture(Ledger.submitRequest(poolConnManager.poolConn_!, finalLedgerReq.req)))
  }

  private def runAsSync[T](f: Future[T]): T = {
    Await.result(f, 15.seconds)
  }

  lazy val INDY_NAMESPACE = "indy:sovrin"
  lazy val UNQUALIFIED_LEDGER_PREFIX = s"did:$INDY_NAMESPACE"
  lazy val legacyLedgerPrefixMapping = Map("did:sov" -> UNQUALIFIED_LEDGER_PREFIX)

  lazy val baseConfig: Config = {
    ConfigFactory.parseString(
      s"""
         |akka {
         |  actor {
         |    provider = "cluster"
         |  }
         |}
         |verity {
         |  lib-vdrtools {
         |
         |    library-dir-location = "/usr/lib"
         |
         |    flavor = "async"
         |
         |    ledger {
         |      indy {
         |        pool-name = "default_pool"
         |        transaction_author_agreement = {
         |          enabled = true
         |
         |          # auto-accept is strictly used for testing and should not be documented as a production feature
         |          auto-accept = true
         |
         |          agreements {
         |            "1.0.0" {
         |               "digest" = "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305"
         |               "mechanism" = "on_file"
         |               "time-of-acceptance" = ${LocalDate.now().toString}
         |             }
         |          }
         |        }
         |
         |        # ledger pool transaction file location
         |        genesis-txn-file-location = "target/genesis.txt"  //environment variable if set, override above value
         |      }
         |    }
         |
         |    wallet {
         |      # this value is provided to libindy's create wallet api by which it knows which type of wallet we want to use
         |      # for now, it only supports "default" and "mysql"
         |      type = "mysql"
         |    }
         |
         |  }
         |  vdr {
         |    unqualified-ledger-prefix = "$UNQUALIFIED_LEDGER_PREFIX"
         |    ledgers: [
         |      {
         |        type = "indy"
         |        namespaces = ["$INDY_NAMESPACE"]
         |        genesis-txn-file-location = "target/genesis.txt"
         |
         |        transaction-author-agreement: {
         |          text: "TAA for sandbox ledger"
         |          version: "1.0.0"
         |          digest: "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305"
         |          time-of-acceptance: ${Instant.now.getEpochSecond}
         |          mechanism: "on_file"
         |        }
         |      }
         |    ]
         |  }
         |}
         |""".stripMargin
    )
  }

  lazy val appConfig: AppConfig = new AppConfig {
    var config: Config = baseConfig
  }
}
