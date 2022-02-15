package com.evernym.verity.protocol.engine

import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service.VDRAdapterUtil.{buildVDRCredDefParams, buildVDRPreparedTxn, buildVDRSchemaParams}
import com.evernym.verity.vdr.service.{VDRAdapterUtil, VDRToolsFactory, VdrTools}
import com.evernym.verity.vdr.{CacheOption, CredDef, DidDoc, FQCredDefId, FQDid, FQSchemaId, LedgerStatus, Namespace, PingResult, PreparedTxn, Schema, SubmittedTxn, VDRAdapter, VdrDid}

import scala.concurrent.{ExecutionContext, Future}


//mock implementation to be used by verity code to interact with VDR/Ledger services in tests
class MockVDRAdapter(vdrToolsFactory: VDRToolsFactory)(implicit executionContext: ExecutionContext)
  extends VDRAdapter {

  val vdrTools: VdrTools = vdrToolsFactory().build()

  override def ping(namespaces: List[Namespace]): Future[PingResult] =
    vdrTools.ping(namespaces).map { result =>
      val finalResult = result.map { case (namespace, result) =>
        namespace -> LedgerStatus(result.isSuccessful)
      }
      PingResult(finalResult)
    }

  override def prepareDidTxn(didJson: String,
                             submitterDID: VdrDid,
                             endorser: Option[String]): Future[PreparedTxn] = {
    vdrTools.prepareSchema(didJson, submitterDID, endorser)
      .map(r => VDRAdapterUtil.buildPreparedTxn(r))
  }


  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String]): Future[PreparedTxn] =
    vdrTools.prepareSchema(buildVDRSchemaParams(schemaJson, fqSchemaId), submitterDID, endorser)
      .map(r => VDRAdapterUtil.buildPreparedTxn(r))

  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String]): Future[PreparedTxn] =
    vdrTools.prepareCredDef(buildVDRCredDefParams(credDefJson, fqCredDefId), submitterDID, endorser)
      .map(r => VDRAdapterUtil.buildPreparedTxn(r))

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte]): Future[SubmittedTxn] = {
    val holder = buildVDRPreparedTxn(preparedTxn)
    vdrTools.submitTxn(holder.namespace, holder.txnBytes, holder.signatureSpec, signature, new String(endorsement))
      .map(r => SubmittedTxn(r))
  }

  override def resolveSchema(fqSchemaId: FQSchemaId,
                             cacheOption: Option[CacheOption]=None): Future[Schema] =
    vdrTools.resolveSchema(fqSchemaId, VDRAdapterUtil.buildVDRCache(cacheOption.getOrElse(CacheOption.default)))
      .map(r => VDRAdapterUtil.buildSchema(fqSchemaId, r))

  override def resolveCredDef(fqCredDefId: FQCredDefId,
                              cacheOption: Option[CacheOption]=None): Future[CredDef] =
    vdrTools.resolveCredDef(fqCredDefId, VDRAdapterUtil.buildVDRCache(cacheOption.getOrElse(CacheOption.default)))
      .map(r => VDRAdapterUtil.buildCredDef(fqCredDefId, r))

  override def resolveDID(fqDid: FQDid,
                          cacheOption: Option[CacheOption]=None): Future[DidDoc] =
    vdrTools.resolveDid(fqDid, VDRAdapterUtil.buildVDRCache(cacheOption.getOrElse(CacheOption.default)))
      .map(r => VDRAdapterUtil.buildDidDoc(r))
}
