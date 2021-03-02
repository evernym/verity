package com.evernym.verity.actor

import com.evernym.verity.Status._
import com.evernym.verity.actor.node_singleton.TrackingParam
import com.evernym.verity.protocol.engine.{DID, Ledgers, VerKey}
import com.evernym.verity.util.TokenProvider
import com.evernym.verity.util.Util._
import com.evernym.verity.Status
import com.evernym.verity.actor.agent.DidPair
import scalapb.GeneratedMessage

/**
 * each actor incoming/outgoing command/response/message is supposed to extend from this base class
 * which helps choosing correct serializer/deserializer if that message needs to use
 * akka remoting (in other way, when a message has to cross a jvm boundary)
 */
trait ActorMessage

final case class ForIdentifier(id: String, msg: Any) extends ActorMessage

final case class ForToken(token: String, msg: Any) extends ActorMessage {
  TokenProvider.checkIfTokenLengthIsValid(token)
}

final case class ForUrlStore(hashedUrl: String, msg: Any) extends ActorMessage {
  checkIfHashedUrlLengthIsValid(hashedUrl)
}

/**
 * agency agent's identity
 * @param DID agency agent's public DID
 * @param verKey agency agent's public DID verKey
 */
case class AgencyPublicDid(DID: DID, verKey: VerKey, ledgers: Option[Ledgers]=None) extends ActorMessage {
  def didPair: DidPair = DidPair(DID, verKey)
}

//event
object Evt {

  //NOTE: the value we choose for these unknown fields should be such that
  // which cannot be used in general
  lazy val defaultUnknownValueForStringType: String = ""

  //used while persisting
  //TODO: instead of creating function for each separate data type,
  // if we can create some generic function that would be good
  def getStringValueFromOption(gv: Option[String]): String = {
    gv match {
      case Some(v: String) => v
      case None => defaultUnknownValueForStringType
      case x => throw new RuntimeException("unhandled value: " + x)
    }
  }

  //used while recovering
  def getOptionFromValue[T](gv: T): Option[T]  = {
    gv match {
      case v:String if v == defaultUnknownValueForStringType => None
      case _:String => Option(gv)
      //keep adding more such cases for different data types
      case x => throw new RuntimeException("unhandled value: " + x)
    }
  }
}

trait MultiEvt {
  def events: Seq[Any]
}

trait State extends GeneratedMessage {

  /**
   * purpose of this is to use it during snapshotting if state size exceeds
   * max allowed size, logging will help know what state contains which might be
   * taking more size
   * @return
   */
  def summary(): Option[String] = None
}

//response msg

case class SendCmdToAllNodes(cmd: Any) extends ActorMessage

case object ConfigRefreshed extends ActorMessage
case object ConfigRefreshFailed extends ActorMessage

case object RefreshConfigOnAllNodes extends ActorMessage
case object RefreshNodeConfig extends ActorMessage
case object NodeConfigRefreshed extends ActorMessage

case object ConfigOverridden extends ActorMessage
case object ConfigOverrideFailed extends ActorMessage

case class OverrideConfigOnAllNodes(configStr: String) extends ActorMessage
case class OverrideNodeConfig(configStr: String) extends ActorMessage
case object NodeConfigOverridden extends ActorMessage

case class GetNodeMetrics(filters: MetricsFilterCriteria) extends ActorMessage
case class GetMetricsOfAllNodes(filters: MetricsFilterCriteria) extends ActorMessage

case class StartProgressTracking(trackingId: TrackingParam) extends ActorMessage
case class StopProgressTracking(trackingId: String) extends ActorMessage
case object NodeMetricsResetDone extends ActorMessage
case object AllNodeMetricsResetDone extends ActorMessage

case class MetricsFilterCriteria(includeMetaData: Boolean = true,
                                 includeTags: Boolean = true,
                                 filtered: Boolean = true)

object MetricsFilterCriteria {

  def apply(includeMetaData: String, includeTags: String, filtered: String): MetricsFilterCriteria = {
    MetricsFilterCriteria(
      strToBoolean(includeMetaData),
      strToBoolean(includeTags),
      strToBoolean(filtered)
    )
  }
}


trait Bad {
  def statusCode: String
  def statusMsg: Option[String]
  def detail: Option[Any]

  def getStatusMsg: String = {
    statusMsg.getOrElse(getStatusMsgFromCode(statusCode))
  }

  def isAlreadyExists: Boolean = statusCode == ALREADY_EXISTS.statusCode

  Status.getFromCode(statusCode)
}
