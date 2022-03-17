package com.evernym.verity.event_bus.event_handlers

import akka.Done
import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.pattern.extended.ask
import akka.util.Timeout
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.endorser_registry.EndorserRegistry.{Cmd, Commands}
import com.evernym.verity.event_bus.ports.consumer.Message
import com.typesafe.config.Config

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

//responsible to handle various events related to 'endorser-registry' (active/inactive etc)
//prepare appropriate command/message from the event and send it over to the appropriate actor
class EndorserRegistryEventHandler(config: Config, singletonParentProxy: ActorRef)(implicit executionContext: ExecutionContext) {

  implicit lazy val timeout: Timeout = Timeout(30.seconds)    //TODO: may be use configuration for the timeout

  def handleMessage(message: Message): Future[Done] = {
    singletonParentProxy
      .ask{ ref: ActorRef => ForEndorserRegistry(createCmd(message, ref))}
      .map(_ => Done)
  }

  //TODO: a high level implementation, it needs to be finalized/corrected as per final changes
  private def createCmd(message: Message, ref: ActorRef): Cmd = {
    val jsonObject = message.cloudEvent

    jsonObject.getString("type") match {

      case TYPE_ENDORSER_ACTIVE =>
        val payload = jsonObject.getJSONObject("data")
        val ledger = payload.getString("ledger")
        val did = payload.getString("did")
        val verKey = payload.getString("verKey")
        Commands.AddEndorser(ledger, did, verKey, ref)

      case TYPE_ENDORSER_INACTIVE =>
        val payload = jsonObject.getJSONObject("data")
        val ledger = payload.getString("ledger")
        val did = payload.getString("did")
        Commands.RemoveEndorser(ledger, did, ref)
    }
  }

}

