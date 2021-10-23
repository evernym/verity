package com.evernym.verity.protocol.engine

import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.{CredDef, DidDoc, FQCredDefId, FQDid, FQSchemaId, Namespace, PingResult, PreparedTxn, Schema, SubmittedTxn, VDRAdapter}

import scala.concurrent.Future

class MockVDRAdapter extends VDRAdapter{
  override def ping(namespaces: List[Namespace]): Future[PingResult] = ???

  override def prepareSchemaTxn(schemaJson: String, fqSchemaId: FQSchemaId, submitterDID: DidStr, endorser: Option[String]): Future[PreparedTxn] = ???

  override def prepareCredDefTxn(credDefJson: String, fqCredDefId: FQCredDefId, submitterDID: DidStr, endorser: Option[String]): Future[PreparedTxn] = ???

  override def submitTxn(preparedTxn: PreparedTxn, signature: Array[Byte], endorsement: Array[Byte]): Future[SubmittedTxn] = ???

  override def resolveSchema(schemaId: FQSchemaId): Future[Schema] = ???

  override def resolveCredDef(credDefId: FQCredDefId): Future[CredDef] = ???

  override def resolveDID(fqDid: FQDid): Future[DidDoc] = ???
}
