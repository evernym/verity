package com.evernym.verity.libindy

import com.evernym.verity.Exceptions.MissingReqFieldException
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{TransactionAuthorAgreement, TxnResp}
import com.evernym.verity.libindy.LedgerTxnExecutorBase._
import com.evernym.verity.protocol.engine.{DID, LedgerRejectException}
import com.evernym.verity.vault._
import org.hyperledger.indy.sdk.pool.Pool

class LedgerTxnExecutorV2(val appConfig: AppConfig,
                          val walletAPI: Option[WalletAPI],
                          val pool: Option[Pool],
                          val currentTAA: Option[TransactionAuthorAgreement])
  extends LedgerTxnExecutorBase{

  def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp = {
    // When something is not found on the ledger, data, txnTime, and seqNo will be null. When any of these three
    // fields are null ipn the response from the ledger, extractOptValue/extractReqValue and thus buildTxnRespForReadOp
    // will result in an InvalidValueException being thrown.
    val result = extractReqValue(resp, RESULT).asInstanceOf[Map[String, Any]]
    val from = extractReqValue(result, IDENTIFIER).toString
    val dest = extractOptValue(result, DEST).asInstanceOf[Option[DID]]
    val data = extractOptValue(result, DATA, nullAllowed = true).map{
      case m: Map[_,_] => m.asInstanceOf[Map[String, Any]] // We can match on type because its been erased
      case s: String => DefaultMsgCodec.fromJson[Map[String,String]](s)
    }
    val txnType = extractReqValue(result, TYPE).toString
    val txnTime = extractOptValue(result, TXN_TIME, nullAllowed = true).map(_.toString.toLong)
    val reqId = extractReqValue(result, REQ_ID).toString.toLong
    val seqNo = extractOptValue(result, SEQ_NO, nullAllowed = true).map(_.toString.toLong)

    TxnResp(from, dest, data, txnType, txnTime, reqId, seqNo)
  }

  def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp = {
    try {
      val result = extractReqValue(resp, RESULT).asInstanceOf[Map[String, Any]]
      val txn = extractReqValue(result, TXN).asInstanceOf[Map[String, Any]]
      val data = extractReqValue(txn, DATA).asInstanceOf[Map[String, Any]]
      val dest = extractOptValue(data, DEST).asInstanceOf[Option[DID]]

      val metaData = extractReqValue(txn, METADATA).asInstanceOf[Map[String, Any]]
      val reqId = extractReqValue(metaData, REQ_ID).toString.toLong
      val from = extractReqValue(metaData, FROM).toString

      val txnType = extractReqValue(txn, TYPE).toString

      val txnMetadata = extractReqValue(result, TXN_METADATA).asInstanceOf[Map[String, Any]]
      val seqNo = extractOptValue(result, SEQ_NO).map(_.toString.toLong)
      val txnTime = extractOptValue(result, TXN_TIME).map(_.toString.toLong)
      TxnResp(from, dest, Option(data), txnType, txnTime, reqId, seqNo)
    } catch {
      case _: MissingReqFieldException => throw LedgerRejectException(resp(REASON).asInstanceOf[String])
    }
  }

}
