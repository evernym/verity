package com.evernym.verity.vdr.service

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.{FQCredDefId, FQSchemaId, VDRToolsFactoryParam}
import com.evernym.verity.vdr.service.VDRActor.Commands.{LedgersRegistered, PrepareCredDefTxn, PrepareSchemaTxn, ResolveSchema, SubmitTxn}
import com.evernym.verity.vdr.service.VDRActor.Replies.{PrepareTxnResp, ResolveSchemaResp, SubmitTxnResp}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

//interacts with provided VDR object
object VDRActor {

  sealed trait Cmd extends ActorMessage
  object Commands {
    case object LedgersRegistered extends Cmd

    case class PrepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String],
                                replyTo: ActorRef[Replies.PrepareTxnResp]) extends Cmd

    case class SubmitTxn(preparedTxn: VDR_PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte],
                         replyTo: ActorRef[Replies.SubmitTxnResp]) extends Cmd

    case class ResolveSchema(schemaId: FQSchemaId,
                             replyTo: ActorRef[Replies.ResolveSchemaResp]) extends Cmd

    case class PrepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String],
                                 replyTo: ActorRef[Replies.PrepareTxnResp]) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class PrepareTxnResp(preparedTxn: Try[VDR_PreparedTxn]) extends Reply
    case class SubmitTxnResp(preparedTxn: Try[VDR_SubmittedTxn]) extends Reply
    case class ResolveSchemaResp(resp: Try[VDR_Schema]) extends Reply
  }

  //implementation of above typed interface
  def apply(vdrToolsFactory: VDRToolsFactory,
            vdrToolsConfig: VDRToolsConfig,
            executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.withStash(10) { buffer =>
        Behaviors.supervise {
          initializing(vdrToolsFactory, vdrToolsConfig)(buffer, actorContext, executionContext)
        }.onFailure[ActorInitializationException](SupervisorStrategy.stop)
      }
    }
  }

  def initializing(vdrToolsFactory: VDRToolsFactory,
                   vdrToolsConfig: VDRToolsConfig)
                  (implicit buffer: StashBuffer[Cmd],
                   actorContext: ActorContext[Cmd],
                   executionContext: ExecutionContext): Behavior[Cmd] = {
    val vdrTools = vdrToolsFactory(VDRToolsFactoryParam(vdrToolsConfig.libraryDirLocation))
    registerLedgers(vdrTools, vdrToolsConfig)
    waitingForLedgerRegistration(vdrTools)
  }

  def waitingForLedgerRegistration(vdrTools: VDRTools)
                                  (implicit  buffer: StashBuffer[Cmd],
                                   actorContext: ActorContext[Cmd],
                                   executionContext: ExecutionContext): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case LedgersRegistered =>
        buffer.unstashAll(ready(vdrTools))

      case other       =>
        buffer.stash(other)
        Behaviors.same
    }

  def ready(vdrTools: VDRTools)(implicit executionContext: ExecutionContext): Behavior[Cmd] =
    Behaviors.receiveMessagePartial {
      case pst: PrepareSchemaTxn    => handlePrepareSchemaTxn(vdrTools, pst)
      case st: SubmitTxn            => handleSubmitTxn(vdrTools, st)
      case rs: ResolveSchema        => handleResolveSchema(vdrTools, rs)
      case pcdt: PrepareCredDefTxn  => handlePrepareCredDefTxn(vdrTools, pcdt)
    }

  private def handlePrepareSchemaTxn(vdrTools: VDRTools,
                                     pst: PrepareSchemaTxn)
                                    (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    handlePrepareTxnResp(
      vdrTools.prepareSchemaTxn(pst.schemaJson, pst.fqSchemaId, pst.submitterDID, pst.endorser),
      pst.replyTo
    )
    Behaviors.same
  }

  private def handlePrepareCredDefTxn(vdrTools: VDRTools,
                                      pcdt: PrepareCredDefTxn)
                                     (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    handlePrepareTxnResp(
      vdrTools.prepareCredDefTxn(pcdt.credDefJson, pcdt.fqCredDefId, pcdt.submitterDID, pcdt.endorser),
      pcdt.replyTo)
    Behaviors.same
  }

  private def handlePrepareTxnResp(fut: Future[VDR_PreparedTxn],
                                   replyTo: ActorRef[PrepareTxnResp])
                                  (implicit executionContext: ExecutionContext): Unit = {
    fut.onComplete {
      case Success(resp) => replyTo ! PrepareTxnResp(Success(resp))
      case Failure(ex)   => replyTo ! PrepareTxnResp(Failure(ex))
    }
  }

  private def handleSubmitTxn(vdrTools: VDRTools,
                              st: SubmitTxn)
                             (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools.submitTxn(st.preparedTxn, st.signature, st.endorsement)
      .onComplete {
        case Success(resp) => st.replyTo ! SubmitTxnResp(Success(resp))
        case Failure(ex)   => st.replyTo ! SubmitTxnResp(Failure(ex))
      }
    Behaviors.same
  }

  private def handleResolveSchema(vdrTools: VDRTools,
                                  rs: ResolveSchema)
                                 (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools.resolveSchema(rs.schemaId)
      .onComplete {
        case Success(resp) => rs.replyTo ! ResolveSchemaResp(Success(resp))
        case Failure(ex)   => rs.replyTo ! ResolveSchemaResp(Failure(ex))
      }
    Behaviors.same
  }

  private def registerLedgers(vdrTools: VDRTools,
                              vdrToolsConfig: VDRToolsConfig)
                             (implicit actorContext: ActorContext[Cmd],
                              executionContext: ExecutionContext): Unit = {
    val futures = Future.sequence(
      vdrToolsConfig.ledgers.map {
        case il: IndyLedger =>
          vdrTools.registerIndyLedger(
            il.namespaces,
            il.genesisTxnFilePath,
            il.taaConfig
          )
      }
    )
    actorContext.pipeToSelf(futures) {
      case Success(_)   => LedgersRegistered
      case Failure(ex)  => throw ex
    }
  }
}
