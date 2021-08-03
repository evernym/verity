package com.evernym.verity.ledger

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.did.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess

import scala.concurrent.Future

case class LedgerExecutorException(message: String,
                              cause: Throwable = None.orNull) extends Exception(message, cause)

trait LedgerTxnExecutor {

  def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Map[String, Any]]

  def addNym(submitter: Submitter, targetDid: DidPair): Future[TxnResp]

  def addAttrib(submitter: Submitter, did: DID, attrName: String, attrValue: String): Future[TxnResp]

  def getTAA(submitter: Submitter): Future[GetTAAResp]

  def getNym(submitter: Submitter, id: String): Future[GetNymResp]

  def getSchema(submitter: Submitter, schemaId: String): Future[GetSchemaResp]

  def writeSchema(submitterDID: DID,
                  schemaJson: String,
                  walletAccess: WalletAccess): Future[TxnResp]

  def prepareSchemaForEndorsement(submitterDID: DID,
                                  schemaJson: String,
                                  endorserDID: DID,
                                  walletAccess: WalletAccess): Future[LedgerRequest]

  def writeCredDef(submitterDID: DID,
                   credDefJson: String,
                   walletAccess: WalletAccess): Future[TxnResp]

  def prepareCredDefForEndorsement(submitterDID: DID,
                                   credDefJson: String,
                                   endorserDID: DID,
                                   walletAccess: WalletAccess): Future[LedgerRequest]

  def getCredDef(submitter: Submitter, credDefId: String): Future[GetCredDefResp]

  def getAttrib(submitter: Submitter, did: DID, attrName: String): Future[GetAttribResp]

  def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp

  def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp
}
