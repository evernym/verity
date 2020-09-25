package com.evernym.verity.libindy

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.ledger.{GetCredDefResp, GetSchemaResp, LedgerSvc, TxnResp}
import com.evernym.verity.protocol.engine.{DID, LedgerAccess, LedgerAccessException, WalletAccess}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


class LedgerAccessApi(ledgerSvc: LedgerSvc, _walletAccess: WalletAccess) extends LedgerAccess {

  private val maxWaitTime: FiniteDuration = 15 second

  override def walletAccess: WalletAccess =  _walletAccess
  override def getCredDef(credDefId: String): Try[GetCredDefResp] = {
    Await.result(
      ledgerSvc.getCreDef(credDefId),
      maxWaitTime
    ) match {
      case Right(resp) => Success(resp)
      case Left(d) => Failure(LedgerAccessException(d.statusMsg))
    }
  }

  override def getSchema(schemaId: String): Try[GetSchemaResp] = {
      Await.result(
        ledgerSvc.getSchema(schemaId),
        maxWaitTime
      ) match {
      case Right(resp) => Success(resp)
      case Left(d) => Failure(LedgerAccessException(d.statusMsg))
    }
  }

  override def writeSchema(submitterDID: String, schemaJson: String): Try[Either[StatusDetail, TxnResp]] = {
    runWithInternalSpan("writeSchema", "LedgerAccessApi") {
      Try(Await.result(
        ledgerSvc.writeSchema(submitterDID, schemaJson, walletAccess),
        maxWaitTime
      ))
    }
  }

  override def writeCredDef(submitterDID: DID,
                            credDefJson: String): Try[Either[StatusDetail, TxnResp]] = {
    runWithInternalSpan("writeCredDef", "LedgerAccessApi") {
      Try(Await.result(
        ledgerSvc.writeCredDef(submitterDID, credDefJson, walletAccess),
        maxWaitTime
      ))
    }
  }
}

object LedgerAccessApi {
  def apply(ledgerSvc: LedgerSvc, walletAccess: WalletAccess) = new LedgerAccessApi(ledgerSvc, walletAccess)
}
