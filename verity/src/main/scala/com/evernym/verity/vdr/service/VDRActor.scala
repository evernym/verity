package com.evernym.verity.vdr.service

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.{FQSchemaId, VDRToolsFactoryParam}
import com.evernym.verity.vdr.service.VDRActor.Commands.{Initialized, PrepareSchemaTxn, SubmitTxn}
import com.evernym.verity.vdr.service.VDRActor.Replies.{PrepareTxnResp, SubmitTxnResp}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

//interacts with provided VDR object
object VDRActor {

  sealed trait Cmd extends ActorMessage
  object Commands {
    case object Initialized extends Cmd

    case class PrepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String],
                                replyTo: ActorRef[Replies.PrepareTxnResp]) extends Cmd

    case class SubmitTxn(preparedTxn: VDR_PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte],
                         replyTo: ActorRef[Replies.SubmitTxnResp]) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class PrepareTxnResp(preparedTxn: Try[VDR_PreparedTxn]) extends Reply
    case class SubmitTxnResp(preparedTxn: Try[VDR_SubmittedTxn]) extends Reply
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
      case Initialized =>
        buffer.unstashAll(initialized(vdrTools))

      case other       =>
        buffer.stash(other)
        Behaviors.same
    }

  def initialized(vdrTools: VDRTools)
                 (implicit executionContext: ExecutionContext): Behavior[Cmd] =
    Behaviors.receiveMessagePartial {
      case pst: PrepareSchemaTxn =>
        handlePrepareTxnResp(
          vdrTools.prepareSchemaTxn(pst.schemaJson, pst.fqSchemaId, pst.submitterDID, pst.endorser),
          pst.replyTo
        )
        Behaviors.same

      case st: SubmitTxn =>
        handleSubmitTxnResp(
          vdrTools.submitTxn(st.preparedTxn, st.signature, st.endorsement),
          st.replyTo
        )
        Behaviors.same
    }

  private def handlePrepareTxnResp(fut: Future[VDR_PreparedTxn],
                                   replyTo:  ActorRef[PrepareTxnResp])
                                  (implicit executionContext: ExecutionContext): Unit = {
    fut.map(resp => replyTo ! PrepareTxnResp(Success(resp)))
    .recover {
      case e: RuntimeException =>
        replyTo ! PrepareTxnResp(Failure(e))
    }
  }

  private def handleSubmitTxnResp(fut: Future[VDR_SubmittedTxn],
                                  replyTo:  ActorRef[SubmitTxnResp])
                                 (implicit executionContext: ExecutionContext): Unit = {
    fut.map(resp => replyTo ! SubmitTxnResp(Success(resp)))
      .recover {
        case e: RuntimeException =>
          replyTo ! SubmitTxnResp(Failure(e))
      }
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
      case Success(_)   => Initialized
      case Failure(ex)  => throw ex
    }
  }
}
