package com.evernym.verity.vdr.service

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.vdr.CreateVDRParam

import scala.concurrent.ExecutionContext

//interacts with provided VDR object
object VDRActor {

  sealed trait Cmd extends ActorMessage

  trait Reply extends ActorMessage

  //implementation of above typed interface
  def apply(vdrCreator: CreateVDR,
            vdrToolsConfig: VDRToolsConfig,
            executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.setup { _ =>
      Behaviors.supervise {
        initializing(vdrCreator, vdrToolsConfig)(executionContext)
      }.onFailure[ActorInitializationException](SupervisorStrategy.stop)
    }
  }

  def initializing(vdrCreator: CreateVDR,
                   vdrToolsConfig: VDRToolsConfig)
                  (implicit executionContext: ExecutionContext): Behavior[Cmd] = {
    val vdr = vdrCreator(CreateVDRParam(vdrToolsConfig.libraryDirLocation))
    registerLedgers(vdr, vdrToolsConfig)
    initialized(vdr)
  }

  def initialized(vdr: VDR)
                 (implicit executionContext: ExecutionContext): Behavior[Cmd] = Behaviors.receiveMessage {
    //TODO: replace with command handlers (to be added in future MRs)
    case _ => Behaviors.same
  }

  private def registerLedgers(vdr: VDR, vdrToolsConfig: VDRToolsConfig): Unit = {
    vdrToolsConfig.ledgers.foreach {
      case il: IndyLedger =>
        vdr.registerIndyLedger(
          il.namespaces,
          il.genesisTxnFilePath,
          il.taaConfig
        )
    }
  }
}
