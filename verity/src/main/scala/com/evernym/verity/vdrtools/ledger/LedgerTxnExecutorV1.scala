package com.evernym.verity.vdrtools.ledger

import akka.actor.ActorSystem
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{TransactionAuthorAgreement, TxnResp}
import com.evernym.verity.vdrtools.ledger.LedgerTxnExecutorBase._
import com.evernym.verity.did.DidStr
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.vdrtools.pool.Pool

import scala.concurrent.ExecutionContext


class LedgerTxnExecutorV1(val actorSystem: ActorSystem,
                          val appConfig: AppConfig,
                          val walletAPI: Option[WalletAPI],
                          val pool: Option[Pool],
                          val currentTAA: Option[TransactionAuthorAgreement],
                          executionContext: ExecutionContext)
  extends LedgerTxnExecutorBase {

  override def futureExecutionContext: ExecutionContext = executionContext

  override def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp = V1TxnRespBuilder.buildTxnRespForReadOp(resp)
  override def buildTxnRespForWriteOp(resp: Map[String, Any]): Unit = V1TxnRespBuilder.buildTxnRespForReadOp(resp)
}

object V1TxnRespBuilder {
  def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp = {
    // When something is not found on the ledger, data, txnTime, and seqNo will be null. When any of these three
    // fields are null in the response from the ledger, extractOptValue/extractReqValue and thus buildTxnRespForReadOp
    // will result in an InvalidValueException being thrown.
    val result = extractReqValue(resp, RESULT).asInstanceOf[Map[String, Any]]
    val data = extractOptValue(result, DATA).map{
      case m: Map[_,_] => m.asInstanceOf[Map[String, Any]] // We can match on type because its been erased
      case s: String => DefaultMsgCodec.fromJson[Map[String,String]](s)
    }
    val from = extractReqValue(result, IDENTIFIER).toString
    val dest = extractOptValue(result, DEST).asInstanceOf[Option[DidStr]]
    val txnType = extractReqValue(result, TYPE).toString
    val txnTime = extractReqValue(result, TXN_TIME).toString.toLong
    val reqId = extractReqValue(result, REQ_ID).toString.toLong
    val seqNo = extractReqValue(result, SEQ_NO).toString.toLong
    TxnResp(from, dest, data, txnType, Some(txnTime), reqId, Some(seqNo))
  }

  def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp = buildTxnRespForReadOp(resp)

}
