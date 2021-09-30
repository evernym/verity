package com.evernym.verity.vdr

import akka.actor.typed.scaladsl.adapter._
import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.evernym.verity.vdr.service.{VDRToolsFactory, VDRActor, VDRToolsConfig}

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

//creates and interacts with VDRActor
class VDRActorAdapter(vdrToolsFactory: VDRToolsFactory,
                      vdrToolsConfig: VDRToolsConfig,
                      apiTimeout: Option[Timeout] = None)
                     (implicit ec: ExecutionContext, system: ActorSystem)
  extends VDRAdapter {

  //until we completely migrate to typed actor system, we are using classic actor system to spawn this `VDRActor` instance.
  //post typed actor system migration, it may/will have to change.
  val vdrActorRef: ActorRef[VDRActor.Cmd] = system.spawnAnonymous(VDRActor(vdrToolsFactory, vdrToolsConfig, ec))
  private implicit val defaultTimeout: Timeout = apiTimeout.getOrElse(Timeout(5, TimeUnit.SECONDS))

}