package com.evernym.verity.vdr

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service.VDRAdapterUtil._
import com.evernym.verity.vdr.service.{VDRActor, VDRToolsConfig, VDRToolsFactory}
import com.evernym.verity.vdr.service.VDRActor.Replies.{PrepareTxnResp, ResolveSchemaResp, SubmitTxnResp}

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String]): Future[PreparedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.PrepareSchemaTxn(schemaJson, fqSchemaId, submitterDID, endorser, ref))
      .map {
        case PrepareTxnResp(Success(txn)) => buildPreparedTxn(txn)
        case PrepareTxnResp(Failure(e))   => throw e
      }
  }

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte]): Future[SubmittedTxn] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.SubmitTxn(buildVDRPreparedTxn(preparedTxn), signature, endorsement, ref))
      .map {
        case SubmitTxnResp(Success(_)) => SubmittedTxn()
        case SubmitTxnResp(Failure(e)) => throw e
      }
  }

  override def resolveSchema(schemaId: FQSchemaId): Future[Schema] = {
    vdrActorRef
      .ask(ref => VDRActor.Commands.ResolveSchema(schemaId, ref))
      .map {
        case ResolveSchemaResp(Success(resp)) => buildSchema(resp)
        case ResolveSchemaResp(Failure(e))    => throw e
      }
  }
}
