package com.evernym.verity.vdr.service

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.evernym.vdrtools.vdr.VdrResults.{PingResult, PreparedTxnResult}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service.VDRActor.Commands._
import com.evernym.verity.vdr.service.VDRActor.Replies._
import com.evernym.verity.vdr._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

//interacts with provided VDR object
object VDRActor {

  sealed trait Cmd extends ActorMessage

  object Commands {
    case object LedgersRegistered extends Cmd

    case class Ping(namespaces: List[Namespace],
                    replyTo: ActorRef[Replies.PingResp]) extends Cmd

    case class PrepareDidTxn(txnSpecificParams: TxnSpecificParams,
                             submitterDid: DidStr,
                             endorser: Option[String],
                             replyTo: ActorRef[Replies.PrepareTxnResp]) extends Cmd

    case class PrepareSchemaTxn(txnSpecificParams: TxnSpecificParams,
                                submitterDid: DidStr,
                                endorser: Option[String],
                                replyTo: ActorRef[Replies.PrepareTxnResp]) extends Cmd

    case class PrepareCredDefTxn(txnSpecificParams: TxnSpecificParams,
                                 submitterDid: DidStr,
                                 endorser: Option[String],
                                 replyTo: ActorRef[Replies.PrepareTxnResp]) extends Cmd

    case class SubmitTxn(namespace: Namespace,
                         txnBytes: Array[Byte],
                         signatureSpec: String,
                         signature: Array[Byte],
                         endorsement: String,
                         replyTo: ActorRef[Replies.SubmitTxnResp]) extends Cmd

    case class ResolveSchema(schemaId: FqSchemaId,
                             cacheOption: Option[CacheOption]=None,
                             replyTo: ActorRef[Replies.ResolveSchemaResp]) extends Cmd

    case class ResolveCredDef(credDefId: FqCredDefId,
                              cacheOption: Option[CacheOption]=None,
                              replyTo: ActorRef[Replies.ResolveCredDefResp]) extends Cmd

    case class ResolveDID(fqDid: FqDID,
                          cacheOption: Option[CacheOption]=None,
                          replyTo: ActorRef[Replies.ResolveDIDResp]) extends Cmd

  }

  trait Reply extends ActorMessage

  object Replies {
    case class PingResp(resp: Try[Map[String, PingResult]]) extends Reply

    case class PrepareTxnResp(preparedTxn: Try[PreparedTxnResult]) extends Reply

    case class SubmitTxnResp(txnResult: Try[TxnResult]) extends Reply

    case class ResolveSchemaResp(resp: Try[VdrSchema]) extends Reply

    case class ResolveCredDefResp(resp: Try[VdrCredDef]) extends Reply

    case class ResolveDIDResp(resp: Try[DidStr]) extends Reply
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
    val vdrToolsBuilder = vdrToolsFactory()
    buildVdrTools(vdrToolsBuilder, vdrToolsConfig)
    waitingForLedgerRegistration(vdrToolsBuilder)
  }

  def waitingForLedgerRegistration(vdrToolsBuilder: VdrToolsBuilder)
                                  (implicit buffer: StashBuffer[Cmd],
                                   actorContext: ActorContext[Cmd],
                                   executionContext: ExecutionContext): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case LedgersRegistered =>
        val vdrTools = vdrToolsBuilder.build();
        buffer.unstashAll(ready(vdrTools))
      case other =>
        buffer.stash(other)
        Behaviors.same
    }

  def ready(vdrTools: VdrTools)(implicit executionContext: ExecutionContext): Behavior[Cmd] =
    Behaviors.receiveMessagePartial {
      case p: Ping => handlePing(vdrTools, p)

      case pdt: PrepareDidTxn => handlePrepareDidTxn(vdrTools, pdt)
      case pst: PrepareSchemaTxn => handlePrepareSchemaTxn(vdrTools, pst)
      case pcdt: PrepareCredDefTxn => handlePrepareCredDefTxn(vdrTools, pcdt)
      case st: SubmitTxn => handleSubmitTxn(vdrTools, st)

      case rs: ResolveSchema => handleResolveSchema(vdrTools, rs)
      case rcd: ResolveCredDef => handleResolveCredDef(vdrTools, rcd)
      case rd: ResolveDID => handleResolveDID(vdrTools, rd)
    }

  private def handlePing(vdrTools: VdrTools,
                         p: Ping)
                        (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .ping(p.namespaces)
      .onComplete(resp => p.replyTo ! PingResp(resp))
    Behaviors.same
  }

  private def handlePrepareDidTxn(vdrTools: VdrTools,
                                  pst: PrepareDidTxn)
                                 (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .prepareDid(pst.txnSpecificParams, pst.submitterDid, pst.endorser)
      .onComplete(resp => pst.replyTo ! PrepareTxnResp(resp))
    Behaviors.same
  }

  private def handlePrepareSchemaTxn(vdrTools: VdrTools,
                                     pst: PrepareSchemaTxn)
                                    (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .prepareSchema(pst.txnSpecificParams, pst.submitterDid, pst.endorser)
      .onComplete(resp => pst.replyTo ! PrepareTxnResp(resp))
    Behaviors.same
  }

  private def handlePrepareCredDefTxn(vdrTools: VdrTools,
                                      pcdt: PrepareCredDefTxn)
                                     (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .prepareCredDef(pcdt.txnSpecificParams, pcdt.submitterDid, pcdt.endorser)
      .onComplete(resp => pcdt.replyTo ! PrepareTxnResp(resp))
    Behaviors.same
  }

  private def handleSubmitTxn(vdrTools: VdrTools,
                              st: SubmitTxn)
                             (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .submitTxn(st.namespace, st.txnBytes, st.signatureSpec, st.signature, st.endorsement)
      .onComplete(resp => st.replyTo ! SubmitTxnResp(resp))
    Behaviors.same
  }

  private def handleResolveSchema(vdrTools: VdrTools,
                                  rs: ResolveSchema)
                                 (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .resolveSchema(rs.schemaId, VDRAdapterUtil.buildVDRCache(rs.cacheOption.getOrElse(CacheOption.default)))
      .onComplete(resp => rs.replyTo ! ResolveSchemaResp(resp))
    Behaviors.same
  }

  private def handleResolveCredDef(vdrTools: VdrTools,
                                   rcd: ResolveCredDef)
                                  (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .resolveCredDef(rcd.credDefId, VDRAdapterUtil.buildVDRCache(rcd.cacheOption.getOrElse(CacheOption.default)))
      .onComplete(resp => rcd.replyTo ! ResolveCredDefResp(resp))
    Behaviors.same
  }

  private def handleResolveDID(vdrTools: VdrTools,
                               rd: ResolveDID)
                              (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    vdrTools
      .resolveDid(rd.fqDid, VDRAdapterUtil.buildVDRCache(rd.cacheOption.getOrElse(CacheOption.default)))
      .onComplete(resp => rd.replyTo ! ResolveDIDResp(resp))
    Behaviors.same
  }

  private def buildVdrTools(vdrToolsBuilder: VdrToolsBuilder,
                            vdrToolsConfig: VDRToolsConfig)
                           (implicit actorContext: ActorContext[Cmd],
                            executionContext: ExecutionContext): Unit = {
    val futures = Future.sequence(
      vdrToolsConfig.ledgers.map {
        case il: IndyLedger =>
          vdrToolsBuilder.registerIndyLedger(
            il.namespaces,
            il.genesisTxnData,
            il.taaConfig
          )
      }
    )
    actorContext.pipeToSelf(futures) {
      case Success(_) => LedgersRegistered
      case Failure(ex) => throw ex
    }
  }
}
