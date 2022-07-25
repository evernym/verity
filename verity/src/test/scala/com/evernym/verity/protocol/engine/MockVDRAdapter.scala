package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.ledger.{GetAttribResp, Submitter}
import com.evernym.verity.vdr.service.VDRAdapterUtil.{buildVDRCredDefParams, buildVDRPreparedTxn, buildVDRSchemaParams}
import com.evernym.verity.vdr.service.{VDRAdapterUtil, VdrTools}
import com.evernym.verity.vdr._

import scala.concurrent.{ExecutionContext, Future}


//mock implementation to be used by verity code to interact with VDR/Ledger services in tests
class MockVDRAdapter(vdrTools: VdrTools)(implicit executionContext: ExecutionContext)
  extends VDRAdapter {

  override def ping(namespaces: List[Namespace]): Future[PingResult] =
    vdrTools
      .ping(namespaces).map { result =>
        val finalResult = result.map { case (namespace, result) =>
          namespace -> LedgerStatus(result.isSuccessful)
        }
        PingResult(finalResult)
      }

  override def prepareDidTxn(didJson: String,
                             submitterDID: FqDID,
                             endorser: Option[String]): Future[PreparedTxn] = {
    vdrTools
      .prepareSchema(didJson, submitterDID, endorser)
      .map(r => VDRAdapterUtil.buildPreparedTxn(r))
  }


  override def prepareSchemaTxn(schemaJson: String,
                                schemaId: SchemaId,
                                submitterDID: FqDID,
                                endorser: Option[String]): Future[PreparedTxn] =
    vdrTools
      .prepareSchema(buildVDRSchemaParams(schemaJson, schemaId), submitterDID, endorser)
      .map(r => VDRAdapterUtil.buildPreparedTxn(r))

  override def prepareCredDefTxn(credDefJson: String,
                                 credDefId: CredDefId,
                                 submitterDID: FqDID,
                                 endorser: Option[String]): Future[PreparedTxn] =
    vdrTools
      .prepareCredDef(buildVDRCredDefParams(credDefJson, credDefId), submitterDID, endorser)
      .map(r => VDRAdapterUtil.buildPreparedTxn(r))

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte]): Future[SubmittedTxn] = {
    val holder = buildVDRPreparedTxn(preparedTxn)
    vdrTools
      .submitTxn(holder.namespace, holder.txnBytes, holder.signatureSpec, signature, new String(endorsement))
      .map(r => SubmittedTxn(r))
  }

  override def resolveSchema(fqSchemaId: FqSchemaId,
                             cacheOption: Option[CacheOption]=None): Future[Schema] =
    vdrTools
      .resolveSchema(fqSchemaId, VDRAdapterUtil.buildVDRCache(cacheOption.getOrElse(CacheOption.default)))
      .map(r => VDRAdapterUtil.buildSchema(fqSchemaId, r))

  override def resolveCredDef(fqCredDefId: FqCredDefId,
                              cacheOption: Option[CacheOption]=None): Future[CredDef] =
    vdrTools
      .resolveCredDef(fqCredDefId, VDRAdapterUtil.buildVDRCache(cacheOption.getOrElse(CacheOption.default)))
      .map(r => VDRAdapterUtil.buildCredDef(fqCredDefId, r))

  override def resolveDID(fqDid: FqDID,
                          cacheOption: Option[CacheOption]=None): Future[DidDoc] =
    vdrTools
      .resolveDid(fqDid, VDRAdapterUtil.buildVDRCache(cacheOption.getOrElse(CacheOption.default)))
      .map(r => VDRAdapterUtil.buildDidDoc(r))


  //============================================= WORKAROUND =======================================================
  //NOTE: this is workaround until vdr tools apis starts supporting updating did docs

  def addNym(submitter: Submitter, didPair: DidPair): Future[Unit] = {
    mockVdrTools.addNym(submitter, didPair)
  }

  def addAttrib(submitter: Submitter, did: DidStr, attrName: AttrName, attrValue: AttrValue): Future[Unit] = {
    mockVdrTools.addAttrib(submitter, did, attrName, attrValue)
  }

  def getAttrib(submitter: Submitter, did: DidStr, attrName: AttrName): Future[GetAttribResp] = {
    mockVdrTools.getAttrib(submitter, did, attrName)
  }

  private lazy val mockVdrTools = vdrTools.asInstanceOf[MockVdrTools]
}
