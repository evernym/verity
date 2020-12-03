package com.evernym.verity.actor

import com.evernym.verity.Status._
import com.evernym.verity.protocol.engine.{DID, Ledgers, VerKey}
import com.evernym.verity.util.TokenProvider
import com.evernym.verity.util.Util._
import com.evernym.verity.Status

/**
 * each actor incoming/outgoing command is supposed to extend from these base classes
 * which helps choosing correct serializer/deserializer if that message needs to use
 * akka remoting (in other way, when a message has to cross a jvm boundary)
 */
trait ActorMessage
trait ActorMessageObject extends ActorMessage
trait ActorMessageClass extends ActorMessage

final case class ForIdentifier(id: String, msg: Any) extends ActorMessageClass

final case class ForToken(token: String, msg: Any) extends ActorMessageClass {
  TokenProvider.checkIfTokenLengthIsValid(token)
}

final case class ForUrlStore(hashedUrl: String, msg: Any) extends ActorMessageClass {
  checkIfHashedUrlLengthIsValid(hashedUrl)
}

/**
 * agency agent's identity
 * @param DID agency agent's public DID
 * @param verKey agency agent's public DID verKey
 */
case class AgencyPublicDid(DID: DID, verKey: VerKey, ledgers: Option[Ledgers]=None) extends ActorMessageClass

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

trait State

//response msg

case class SendCmdToAllNodes(cmd: Any) extends ActorMessageClass

case object ConfigRefreshed extends ActorMessageObject
case object ConfigRefreshFailed extends ActorMessageObject

case object RefreshConfigOnAllNodes extends ActorMessageObject
case object RefreshNodeConfig extends ActorMessageObject
case object NodeConfigRefreshed extends ActorMessageObject

case object ConfigOverridden extends ActorMessageObject
case object ConfigOverrideFailed extends ActorMessageObject

case class OverrideConfigOnAllNodes(configStr: String) extends ActorMessageClass
case class OverrideNodeConfig(configStr: String) extends ActorMessageClass
case object NodeConfigOverridden extends ActorMessageObject

case class GetNodeMetrics(filters: MetricsFilterCriteria) extends ActorMessageClass
case class GetMetricsOfAllNodes(filters: MetricsFilterCriteria) extends ActorMessageClass

case object ResetNodeMetrics extends ActorMessageObject
case object ResetMetricsOfAllNodes extends ActorMessageObject
case class StartProgressTracking(trackingId: String) extends ActorMessageClass
case class StopProgressTracking(trackingId: String) extends ActorMessageClass
case object NodeMetricsResetDone extends ActorMessageObject
case object AllNodeMetricsResetDone extends ActorMessageObject

case class MetricsFilterCriteria(includeMetaData: Boolean = true, includeReset: Boolean = true,
                                 includeTags: Boolean = true, filtered: Boolean = true)

object MetricsFilterCriteria {

  def apply(includeMetaData: String, includeReset: String, includeTags: String, filtered: String): MetricsFilterCriteria = {
    MetricsFilterCriteria(strToBoolean(includeMetaData), strToBoolean(includeReset),
      strToBoolean(includeTags), strToBoolean(filtered))
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
