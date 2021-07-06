package com.evernym.verity.actor.url_mapper

import akka.actor.Props
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.{ActorMessage, HasProps}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.urlmapper.UrlAdded


/**
 * This actor gets created for each "hashed url", and it stores corresponding "long url".
 * The "hashed url" becomes "entity id" and the "long url" is stored
 * as a state variable inside the actor.
 *
 * This is hosted on CAS (for now) and used by apps (connect.me etc) to fetch "long url"
 * for a given "hashed url" (during accept invite process)
 *
 */
class UrlStore(val appConfig: AppConfig) extends BasePersistentActor {

  var url: Option[String] = None

  override lazy val persistenceEncryptionKey: String = appConfig.getStringReq(CommonConfig.SECRET_URL_STORE)

  // This is for event sourcing; it is called when the actor starts for the first
  // time (once for each event persisted from DynamoDB); after, all new events
  // are persisted as well.
  val receiveEvent: Receive = {
    case e: UrlAdded => url = Option(e.url)
  }

  lazy val receiveCmd: Receive = {
    case _: AddUrl if url.isDefined =>
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode)

    case c: AddUrl =>
      // Persist the event; then send it back to the sender.
      writeApplyAndSendItBack(UrlAdded(c.url))

    case GetActualUrl => sender ! url
  }

}

object UrlStore extends HasProps {
  def props(implicit appConfig: AppConfig): Props = Props(new UrlStore(appConfig))
}

//cmds
case class AddUrl(url: String) extends ActorMessage
case object GetActualUrl extends ActorMessage
