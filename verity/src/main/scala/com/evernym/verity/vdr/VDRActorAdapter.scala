package com.evernym.verity.vdr

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.evernym.verity.did.{DidStr, VerKeyStr}
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

  private implicit val defaultTimeout: Timeout = apiTimeout.getOrElse(Timeout(5, TimeUnit.SECONDS))

  override def ping(namespaces: List[Namespace]): Future[PingResult] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.Ping(namespaces, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildPingResult(resp))
  }

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: VdrDid,
                                endorser: Option[String]): Future[PreparedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareSchemaTxn(buildVDRSchemaParams(schemaJson, fqSchemaId), submitterDID, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
      .map(resp => buildPreparedTxn(resp))
  }

  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: VdrDid,
                                 endorser: Option[String]): Future[PreparedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareCredDefTxn(buildVDRCredDefParams(credDefJson, fqCredDefId), submitterDID, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
      .map(resp => buildPreparedTxn(resp))
  }

  override def prepareDIDTxn(didJson: String,
                             did: DidStr,
                             submitterDID: DidStr,
                             endorser: Option[String]): Future[PreparedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareDIDTxn(didJson, submitterDID, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
      .map(resp => buildPreparedTxn(resp))
  }


  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte]): Future[SubmittedTxn] = {
    val holder = buildVDRPreparedTxn(preparedTxn)
    vdrActorRef
      .ask(ref => VDRActor.Commands.SubmitTxn(holder.namespace,
        holder.txnBytes,
        holder.signatureSpec,
        signature,
        new String(endorsement),
        ref))
      .flatMap(reply => Future.fromTry(reply.txnResult))
      .map(resp => SubmittedTxn(resp))
  }

  override def resolveSchema(schemaId: FQSchemaId): Future[Schema] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveSchema(schemaId, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildSchema(resp))
  }

  override def resolveCredDef(credDefId: FQCredDefId): Future[CredDef] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveCredDef(credDefId, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildCredDef(resp))
  }

  override def resolveDID(fqDid: FQDid): Future[DidDoc] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveDID(fqDid, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
      .map(resp => buildDidDoc(resp))
  }

}
