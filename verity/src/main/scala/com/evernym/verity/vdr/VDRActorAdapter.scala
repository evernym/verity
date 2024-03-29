package com.evernym.verity.vdr

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.evernym.verity.vdr.service.VDRAdapterUtil._
import com.evernym.verity.vdr.service.{VDRActor, VDRToolsConfig, VDRToolsFactory}

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}


//creates and interacts with VDRActor
class VDRActorAdapter(vdrToolsFactory: VDRToolsFactory,
                      vdrToolsConfig: VDRToolsConfig,
                      apiTimeout: Option[Timeout] = None)
                     (implicit ec: ExecutionContext, system: ActorSystem[Nothing])
  extends VDRAdapter {

  //until we completely migrate to typed actor system, we are using classic actor system to spawn this `VDRActor` instance.
  //post typed actor system migration, it may/will have to change.
  val vdrActorRef: ActorRef[VDRActor.Cmd] = system.toClassic.spawnAnonymous(VDRActor(vdrToolsFactory, vdrToolsConfig, ec))

  private implicit val defaultTimeout: Timeout = apiTimeout.getOrElse(Timeout(30, TimeUnit.SECONDS))

  override def ping(namespaces: List[Namespace]): Future[PingResult] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.Ping(namespaces, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildPingResult(resp))
  }

  override def prepareDidTxn(didJson: String, submitterDID: VdrDid, endorser: Option[String]): Future[PreparedTxn] =
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareDidTxn(didJson, submitterDID, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
      .map(resp => buildPreparedTxn(resp))

  override def prepareSchemaTxn(schemaJson: String,
                                schemaId: FqSchemaId,
                                submitterDID: VdrDid,
                                endorser: Option[String]): Future[PreparedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareSchemaTxn(buildVDRSchemaParams(schemaJson, schemaId), submitterDID, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
      .map(resp => buildPreparedTxn(resp))
  }

  override def prepareCredDefTxn(credDefJson: String,
                                 credDefId: FqCredDefId,
                                 submitterDID: VdrDid,
                                 endorser: Option[String]): Future[PreparedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareCredDefTxn(buildVDRCredDefParams(credDefJson, credDefId), submitterDID, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
      .map(resp => buildPreparedTxn(resp))
  }

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte]): Future[SubmittedTxn] = {
    val holder = buildVDRPreparedTxn(preparedTxn)
    vdrActorRef
      .ask(ref => VDRActor.Commands.SubmitTxn(
        holder.namespace,
        holder.txnBytes,
        holder.signatureSpec,
        signature,
        if (endorsement.isEmpty) null else new String(endorsement),
        ref))
      .flatMap(reply => Future.fromTry(reply.txnResult))
      .map(resp => SubmittedTxn(resp))
  }

  override def resolveSchema(fqSchemaId: FqSchemaId, cacheOption: Option[CacheOption]=None): Future[Schema] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveSchema(fqSchemaId, cacheOption, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildSchema(fqSchemaId, resp))
  }

  override def resolveCredDef(fqCredDefId: FqCredDefId, cacheOption: Option[CacheOption]=None): Future[CredDef] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveCredDef(fqCredDefId, cacheOption, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildCredDef(fqCredDefId, resp))
  }

  override def resolveDID(fqDid: FqDID, cacheOption: Option[CacheOption]=None): Future[DidDoc] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveDID(fqDid, cacheOption, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildDidDoc(resp))
  }

}
