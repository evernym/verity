package com.evernym.verity.vdr

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service.{PingResult, VDRActor, VDRToolsConfig, VDRToolsFactory}

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

  override def ping(namespaces: List[Namespace]): Future[Map[String, PingResult]] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.Ping(namespaces, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
  }

  override def prepareSchemaTxn(txnSpecificParams: TxnSpecificParams,
                                submitterDid: DidStr,
                                endorser: Option[String]): Future[PreparedTxnResult] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareSchemaTxn(txnSpecificParams, submitterDid, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
  }

  override def prepareCredDefTxn(txnSpecificParams: TxnSpecificParams,
                                 submitterDid: DidStr,
                                 endorser: Option[String]): Future[PreparedTxnResult] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareCredDefTxn(txnSpecificParams, submitterDid, endorser, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
  }

  override def submitTxn(namespace: Namespace,
                         txnBytes: Array[Byte],
                         signatureSpec: String,
                         signature: Array[Byte],
                         endorsement: String): Future[TxnResult] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.SubmitTxn(namespace, txnBytes, signatureSpec, signature, endorsement, ref))
      .flatMap(reply => Future.fromTry(reply.preparedTxn))
  }

  override def resolveSchema(schemaId: FQSchemaId): Future[Schema] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveSchema(schemaId, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
  }

  override def resolveCredDef(credDefId: FQCredDefId): Future[CredDef] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveCredDef(credDefId, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
  }

  override def resolveDID(fqDid: FQDid): Future[Did] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveDID(fqDid, ref))
      .flatMap(reply => Future.fromTry(reply.resp))
  }
}
