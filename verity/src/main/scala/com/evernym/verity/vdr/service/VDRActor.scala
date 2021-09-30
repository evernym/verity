package com.evernym.verity.vdr.service

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{Behavior, SupervisorStrategy}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.vdr.VDRToolsFactoryParam
import com.evernym.verity.vdr.service.VDRActor.Commands.Initialized

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//interacts with provided VDR object
object VDRActor {

  sealed trait Cmd extends ActorMessage
  object Commands {
    case object Initialized extends Cmd
  }
  trait Reply extends ActorMessage

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
    initialing(vdrTools)
  }

  def initialing(vdrTools: VDRTools)
                 (implicit  buffer: StashBuffer[Cmd],
                  actorContext: ActorContext[Cmd],
                  executionContext: ExecutionContext): Behavior[Cmd] = Behaviors.receiveMessage {
    case Initialized =>
      buffer.unstashAll(initialized(vdrTools))

    case other       =>
      buffer.stash(other)
      Behaviors.same
  }

  def initialized(vdrTools: VDRTools)
                 (implicit actorContext: ActorContext[Cmd],
                  executionContext: ExecutionContext): Behavior[Cmd] = Behaviors.receiveMessage {
    //TODO: replace with command handlers (to be added in future MRs)
    case _ => Behaviors.same
  }

  private def registerLedgers(vdrTools: VDRTools, vdrToolsConfig: VDRToolsConfig)
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
