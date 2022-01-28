package com.evernym.verity.testkit.util

import com.evernym.verity.util2.Status.StatusDetailException
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerRequest, Submitter, TransactionAuthorAgreement}
import com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.testkit.HasDefaultTestWallet
import com.evernym.verity.util.OptionUtil
import com.evernym.verity.util.Util._
import com.evernym.verity.vault._
import com.typesafe.scalalogging.Logger
import com.evernym.vdrtools.ledger.Ledger._
import com.evernym.vdrtools.pool.Pool
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}


class LedgerUtil (val appConfig: AppConfig,
                  val poolConfigName: Option[String],
                  executionContext: ExecutionContext,
                  val submitterDID: DidStr = "Th7MpTaRZVRYnPiabds81Y",
                  val submitterKeySeed: String = "000000000000000000000000Steward1",
                  val submitterRole: String = "STEWARD",
                  val taa: Option[TransactionAuthorAgreement] = None,
                  val genesisTxnPath: Option[String] = None)
  extends CommonSpecUtil
    with HasDefaultTestWallet {

  override def createWallet: Boolean = true

  val logger: Logger = getLoggerByClass(getClass)

  lazy val agentWalletId: Option[String] = {
    Option(submitterDID + "_" + LocalDateTime.now().toString)
  }

  lazy val poolConnManager: LedgerPoolConnManager = {
    val pc = new IndyLedgerPoolConnManager(
      ActorSystemVanilla("ledger-pool"),
      appConfig,
      executionContext,
      poolConfigName,
      genesisTxnPath
    )
    pc.open()
    pc
  }
  // Read requests don't require a particular submitterDID, so here is a random one
  private val privateGetDID: DidStr = "KZyKVMqt5ShMvxLF1zKM7F"
  private val respWaitTime: FiniteDuration = Duration.create(20, TimeUnit.SECONDS)

  Pool.setProtocolVersion(LEDGER_TXN_PROTOCOL_V2).get

  try {
    setupWallet(submitterDID, submitterKeySeed)
  } catch {
    case e: Exception => throw new Exception("error occurred during setting up trustee wallet: " + e.getMessage)
  }

  def executeLedgerRequest(req: String): LedgerResponse = {
    poolConnManager.close()
    poolConnManager.open()
    val fut = poolConnManager
      .txnExecutor(Some(testWalletAPI))
      .completeRequest(Submitter(submitterDID, Some(wap)), LedgerRequest(req, taa = poolConnManager.currentTAA))
    val status = Await.ready(fut, respWaitTime).value.get
    status match {
      case Success(s: Any) =>
        poolConnManager.close()
        LedgerResponse(s)
      case Failure(StatusDetailException(sd)) => throw new Exception(s"Unable to execute ledger request. Reason: $sd")
      case Failure(e) => throw new Exception(s"Unable to execute ledger request. Reason: $e")
    }
  }

  def getWalletName(did: DidStr): String = did + "local"

  def setupWallet(did: DidStr, seed: String): NewKeyCreated = {
    testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(Option(did), Option(seed)))
  }

  def bootstrapNewDID(did: DidStr, verKey: VerKeyStr, role: String = null): Unit = {
    val req = buildGetNymRequest(privateGetDID, did).get
    val response = executeLedgerRequest(req)

    // Do not write a DID that is already written to the ledger
    if (isFoundOnLedger(response) && findData(response).contains(did)) {
      ()
    } else {
      submitterRole match {
        case "STEWARD" =>
          val req = buildNymRequest(submitterDID, did, verKey, null, role).get
          executeLedgerRequest(req)
        case "ENDORSER" =>
          if(OptionUtil.blankOption(role).isEmpty) {
            val req = buildNymRequest(submitterDID, did, verKey, null, role).get
            executeLedgerRequest(req)
          }
          else {
            println("************* WRITE DID TO LEDGER ***************")
            println("** The submitter don't have permission to write ")
            println("** the DID to ledger, this must be done manually")
            println("** ")
            println(s"** DID: $did")
            println(s"** Verkey: $verKey")
            println(s"**")
          }
      }
    }
  }

  def sendAddAttrib(raw: String): LedgerResponse = {
    val req = buildAttribRequest(submitterDID, submitterDID, null, raw, null).get
    executeLedgerRequest(req)
  }

  def sendAddAttrib(name: String, value: String): LedgerResponse = {
    val raw = getJsonStringFromMap (Map(name -> value))
    sendAddAttrib(raw)
  }

  def sendGetAttrib(did: DidStr, attrName: String): LedgerResponse = {
    val req = buildGetAttribRequest(submitterDID, did, attrName, null, null).get
    executeLedgerRequest(req)
  }

  def setEndpointUrl(did: DidStr, ep: String): LedgerResponse = {
    val raw = getJsonStringFromMap (Map(URL -> ep))
    sendAddAttrib(raw)
  }

  private def findData(ledgerResp: LedgerResponse): String = {
    //TODO there has to be a better way!?!
    ledgerResp.resp
      .asInstanceOf[Map[String, Any]]("result")
      .asInstanceOf[Map[String, Any]]("data")
      .toString
  }

  /**
   * Checks if a Ledger response indicates that a txn was found on the ledger
   *
   * 'data' will be null, but check 'seqNo' and 'txnTime' for null to detect if a txn is NOT found on the ledger
   *
   * @param resp The Response from the Ledger
   */
  private def isFoundOnLedger(resp: LedgerResponse): Boolean = {
    val result = resp.resp.asInstanceOf[Map[String, Any]]("result")
    if (result != null) {
      val seqNo = result.asInstanceOf[Map[String, Any]]("seqNo")
      val txnTime = result.asInstanceOf[Map[String, Any]]("txnTime")
      return seqNo != null && txnTime != null
    }
    false
  }

  /**
    * Checks that response is a valid txn from the ledger
    *
    * @param resp The Response from the Ledger
    */
  private def checkTxn(resp: LedgerResponse): Unit = {
    val seqNo = resp.resp.asInstanceOf[Map[String, Any]]("result").asInstanceOf[Map[String, Any]]("seqNo")
    assert(seqNo != null, "seqNo must not be null")
    assert(seqNo.isInstanceOf[Int], "seqNo must be a number")
    assert(seqNo.asInstanceOf[Int] > 0, "seqNo must be greater than zero")

    val txnTime = resp.resp.asInstanceOf[Map[String, Any]]("result").asInstanceOf[Map[String, Any]]("txnTime")
    assert(txnTime != null, "txnTime must not be null")
    assert(txnTime.isInstanceOf[Int], "txnTime must be a number")
    assert(txnTime.asInstanceOf[Int] > 0, "txnTime must be greater than zero")
  }

  def checkSchemaOnLedger(did: DidStr, name: String, version:String): Unit = {
    val schema_id = s"$did:2:$name:$version"
    val req = buildGetSchemaRequest(privateGetDID, schema_id).get
    val response = executeLedgerRequest(req)

    checkTxn(response)

    val data = findData(response)

    assert(data.contains(name))
    assert(data.contains(version))
  }

  def checkCredDefOnLedger(credDefId: String): Unit = {
    logger.info(s"credDefId: $credDefId\n")
    val req = buildGetCredDefRequest(privateGetDID, credDefId).get
    val response = executeLedgerRequest(req)

    checkTxn(response)
  }

  def checkDidOnLedger(did: DidStr, verkey: VerKeyStr, role: String = null): Unit = {
    val req = buildGetNymRequest(privateGetDID, did).get
    val response = executeLedgerRequest(req)

    checkTxn(response)

    val data = findData(response)

    assert(data.contains(did))
    assert(data.contains(verkey))

    role match {
      case "ENDORSER" => assert(data.contains("\"role\":\"101\""))
      case _ =>
    }
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}
