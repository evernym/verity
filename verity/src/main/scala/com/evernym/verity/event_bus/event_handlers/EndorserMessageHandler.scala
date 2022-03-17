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

//responsible to handle various messages related to 'endorser' (active/inactive etc)
//prepare appropriate command/message and send it over to the appropriate actor
class EndorserMessageHandler(config: Config,
                             singletonParentProxy: ActorRef)
                            (implicit executionContext: ExecutionContext) {

  implicit lazy val timeout: Timeout = Timeout(30.seconds)    //TODO: may be use configuration for the timeout

  def handleMessage(message: Message): Future[Done] = {
    singletonParentProxy
      .ask{ ref: ActorRef => ForEndorserRegistry(createCmd(message, ref))}
      .map(_ => Done)
  }

  //TODO: a high level implementation, it needs to be finalized/corrected as per final changes
  private def createCmd(message: Message, ref: ActorRef): Cmd = {
    val cloudEvent = message.cloudEvent

    cloudEvent.getString(CLOUD_EVENT_TYPE) match {

      case EVENT_ENDORSER_ACTIVATED =>
        val payload = cloudEvent.getJSONObject(CLOUD_EVENT_DATA)
        val ledger = payload.getString(DATA_FIELD_LEDGER)
        val did = payload.getString(DATA_FIELD_DID)
        val verKey = payload.getString(DATA_FIELD_VER_KEY)
        Commands.AddEndorser(ledger, did, verKey, ref)

      case EVENT_ENDORSER_DEACTIVATED =>
        val payload = cloudEvent.getJSONObject(CLOUD_EVENT_DATA)
        val ledger = payload.getString(DATA_FIELD_LEDGER)
        val did = payload.getString(DATA_FIELD_DID)
        Commands.RemoveEndorser(ledger, did, ref)
    }
  }

  //constants
  val DATA_FIELD_LEDGER = "ledger"
  val DATA_FIELD_DID = "did"
  val DATA_FIELD_VER_KEY = "verKey"

}

