package com.evernym.verity.testkit.util

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerRequest, Submitter, TransactionAuthorAgreement}
import com.evernym.verity.libindy.LibIndyWalletProvider
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.OptionUtil
import com.evernym.verity.util.Util._
import com.evernym.verity.vault._
import org.hyperledger.indy.sdk.ledger.Ledger._
import org.hyperledger.indy.sdk.pool.Pool

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}


class LedgerUtil (
                   val appConfig: AppConfig,
                   val poolConnManager: LedgerPoolConnManager,
                   val submitterDID: DID = "Th7MpTaRZVRYnPiabds81Y",
                   val submitterKeySeed: String = "000000000000000000000000Steward1",
                   val submitterRole: String = "STEWARD",
                   val taa: Option[TransactionAuthorAgreement] = None
) extends CommonSpecUtil {

  // Read requests don't require a particular submitterDID, so here is a random one
  private val privateGetDID: DID = "KZyKVMqt5ShMvxLF1zKM7F"
  private val walletName = submitterDID + "_" + LocalDateTime.now().toString

  private val walletAPI = new WalletAPI(new LibIndyWalletProvider(appConfig), TestUtil, poolConnManager)

  private val respWaitTime: FiniteDuration = Duration.create(20, TimeUnit.SECONDS)

  private implicit val wap: WalletAccessParam = createOrOpenWallet(walletName, walletAPI)

//  var currentTAA = taa
//  var defaultTAA = ConfigUtil.findTAAConfig(appConfig, "1.0.0")
//
//  def taaToUse(): Option[TransactionAuthorAgreement] = {
//    currentTAA match {
//      case Some(_)  => currentTAA
//      case None     => defaultTAA
//    }
//  }

  Pool.setProtocolVersion(LEDGER_TXN_PROTOCOL_V2).get

  try {
    setupWallet(submitterDID, submitterKeySeed)
  } catch {
    case e: Exception => println("error occurred during setting up trustee wallet: " + e.getMessage)
  }

  def executeLedgerRequest(req: String): LedgerResponse = {
    poolConnManager.close()
    poolConnManager.open()
    println("ledger request: " + req)
    val fut = poolConnManager
      .txnExecutor(Some(walletAPI))
      .completeRequest(Submitter(submitterDID, Some(wap)), LedgerRequest(req, taa = poolConnManager.currentTAA))

    val status = Await.result(fut, respWaitTime)
    status match {
      case Right(s: Any) =>
        println("ledger response: " + status)
        poolConnManager.close()
        LedgerResponse(s)
      case e => throw new Exception(s"Unable to execute ledger request. Reason: $e")
    }
  }

  def getWalletName(did: DID): String = did + "local"

  def setupWallet(did: DID, seed: String): NewKeyCreated = {
    walletAPI.createNewKey(CreateNewKeyParam(Option(did), Option(seed)))
  }

  def bootstrapNewDID(did: DID, verKey: VerKey, role: String = null): Unit = {
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

  def sendGetAttrib(did: DID, attrName: String): LedgerResponse = {
    val req = buildGetAttribRequest(submitterDID, did, attrName, null, null).get
    executeLedgerRequest(req)
  }

  def setEndpointUrl(did: DID, ep: String): LedgerResponse = {
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

  def checkSchemaOnLedger(did: DID, name: String, version:String): Unit = {
    val schema_id = s"$did:2:$name:$version"
    val req = buildGetSchemaRequest(privateGetDID, schema_id).get
    val response = executeLedgerRequest(req)

    checkTxn(response)

    val data = findData(response)

    assert(data.contains(name))
    assert(data.contains(version))
  }

  def checkCredDefOnLedger(credDefId: String): Unit = {
    print(s"credDefId: $credDefId\n")
    val req = buildGetCredDefRequest(privateGetDID, credDefId).get
    val response = executeLedgerRequest(req)

    checkTxn(response)
  }

  def checkDidOnLedger(did: DID, verkey: VerKey, role: String = null): Unit = {
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
}
