package com.evernym.verity.eventing.event_handlers

import akka.Done
import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.pattern.extended.ask
import akka.util.Timeout
import com.evernym.verity.actor.cluster_singleton.ForEndorserRegistry
import com.evernym.verity.endorser_registry.EndorserRegistry.{Cmd, Commands}
import com.evernym.verity.eventing.event_handlers.EndorserMessageHandler._
import com.evernym.verity.eventing.ports.consumer.Message
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

//responsible to handle various messages related to 'endorser' (active/inactive etc)
//prepare appropriate command/message and send it over to the appropriate actor
class EndorserMessageHandler(config: Config,
                             singletonParentProxy: ActorRef)
                            (implicit executionContext: ExecutionContext) {

  implicit lazy val timeout: Timeout = Timeout(30.seconds)    //TODO: may be use configuration for the timeout
  val logger: Logger = getLoggerByClass(getClass)

  def handleMessage(message: Message): Future[Done] = {
    try {
      singletonParentProxy
        .ask { ref: ActorRef => ForEndorserRegistry(createCmd(message, ref)) }
        .map(_ => Done)
    } catch {
      case _: MatchError =>
        logger.info("endorser message handler not handling event of type: " + message.cloudEvent.getString(CLOUD_EVENT_TYPE))
        Future.successful(Done)
    }
  }

  //TODO: a high level implementation, it needs to be finalized/corrected as per final changes
  private def createCmd(message: Message, ref: ActorRef): Cmd = {
    val cloudEvent = message.cloudEvent

    cloudEvent.getString(CLOUD_EVENT_TYPE) match {

      case EVENT_ENDORSER_ACTIVATED_V1 =>
        val payload = cloudEvent.getJSONObject(CLOUD_EVENT_DATA)
        val ledger = payload.getString(DATA_FIELD_LEDGER_PREFIX)
        val did = payload.getString(DATA_FIELD_ENDORSER_DID)
        Commands.AddEndorser(ledger, did, ref)

      case EVENT_ENDORSER_DEACTIVATED_V1 =>
        val payload = cloudEvent.getJSONObject(CLOUD_EVENT_DATA)
        val ledger = payload.getString(DATA_FIELD_LEDGER_PREFIX)
        val did = payload.getString(DATA_FIELD_ENDORSER_DID)
        Commands.RemoveEndorser(ledger, did, ref)
    }
  }

}

object EndorserMessageHandler {
  //constants
  val DATA_FIELD_ENDORSER_DID = "endorserdid"
}