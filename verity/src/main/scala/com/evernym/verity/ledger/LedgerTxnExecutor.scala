package com.evernym.verity.ledger

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess

import scala.concurrent.Future

case class LedgerExecutorException(message: String,
                              cause: Throwable = None.orNull) extends Exception(message, cause)

trait LedgerTxnExecutor {

  def completeRequest(submitter: Submitter, req: LedgerRequest):
  Future[Either[StatusDetail, Map[String, Any]]]

  def addNym(submitter: Submitter, targetDid: DidPair):
    Future[Either[StatusDetail, TxnResp]]

  def addAttrib(submitter: Submitter, did: DID, attrName: String, attrValue: String):
    Future[Either[StatusDetail, TxnResp]]

  def getTAA(submitter: Submitter):
    Future[Either[StatusDetail, GetTAAResp]]

  def getNym(submitter: Submitter, id: String):
    Future[Either[StatusDetail, GetNymResp]]

  def getSchema(submitter: Submitter, schemaId: String):
    Future[Either[StatusDetail, GetSchemaResp]]

  def writeSchema(submitterDID: DID,
                  schemaJson: String,
                  walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]]

  def prepareSchemaForEndorsement(submitterDID: DID,
                                  schemaJson: String,
                                  endorserDID: DID,
                                  walletAccess: WalletAccess): LedgerRequest

  def writeCredDef(submitterDID: DID,
                   credDefJson: String,
                   walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]]

  def prepareCredDefForEndorsement(submitterDID: DID,
                                   credDefJson: String,
                                   endorserDID: DID,
                                   walletAccess: WalletAccess): LedgerRequest

  def getCredDef(submitter: Submitter, credDefId: String):
  Future[Either[StatusDetail, GetCredDefResp]]

  def getAttrib(submitter: Submitter, did: DID, attrName: String):
    Future[Either[StatusDetail, GetAttribResp]]

  def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp

  def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp
}
