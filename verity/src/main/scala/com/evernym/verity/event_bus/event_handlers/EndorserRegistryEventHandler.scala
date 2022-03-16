package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.endorser_registry.EndorserRegistry.{Cmd, Commands}
import com.evernym.verity.event_bus.ports.consumer.Message

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

//responsible to handle various events related to 'endorser-registry' (active/inactive etc)
//prepare appropriate command/message from the event and send it over to the appropriate actor

class EndorserRegistryEventHandler(singletonParentProxy: ActorRef)(implicit executionContext: ExecutionContext) {

  implicit lazy val timeout = Timeout(10.seconds)

  def handleMessage(message: Message): Future[Done] = {
    singletonParentProxy.ask(ref => createCmd(message, ref)).map(_ => Done)
  }

  //TODO: just high level implementation to given an idea, it needs to change once we know the final event schema
  private def createCmd(message: Message, ref: ActorRef): Cmd = {
    val jsonObject = message.cloudEvent
    jsonObject.getString("type") match {
      case "add_endorser" =>
        val ledger = jsonObject.getString("ledger")
        val did = jsonObject.getString("did")
        val verKey = jsonObject.getString("verKey")
        Commands.AddEndorser(ledger, did, verKey, ref.toTyped)
    }
  }

}

