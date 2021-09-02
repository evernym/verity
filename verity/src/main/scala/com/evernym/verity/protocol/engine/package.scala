package com.evernym.verity.protocol

import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgId, MsgType, TypedMsgLike}
import com.evernym.verity.protocol.engine._

package object engine {

  type PinstId = String //Protocol Instance Id

  type ParameterName = String
  type ParameterValue = String

  type Ledgers = List[Map[String, Any]]

  type ContextId = String
  type RelationshipId = String
  type DomainId = String
  type StorageId = String

  type ThreadId = String
  type RefMsgId = String

  val DomainIdFieldName = "domain_did"

  type Nonce = String

  //NOTE: until we implement threading, we'll use below mentioned thread id
  val DEFAULT_THREAD_ID = "0"

  type ProtoDef = ProtocolDefinition[_,_,_,_,_,_]

  case class Parameter(name: ParameterName, value: ParameterValue)

  class ProtocolEngineException(msg: String) extends RuntimeException(msg)

  /**
    * Identifier for participants. For the most part, the Protocol relies on
    * the Container to decrypt and authenticate incoming messages.
    */
  type ParticipantId = String

  /**
    * Index for a participant in a protocol's 'participants' vector.
    */
  type ParticipantIndex = Int

  type ProtoReceive = PartialFunction[Any, Any]

  class EmptyValueForOptionalFieldProtocolEngineException(statusMsg: String)
    extends ProtocolEngineException(statusMsg)

  class MissingReqFieldProtocolEngineException(statusMsg: String)
    extends ProtocolEngineException(statusMsg)

  class InvalidFieldValueProtocolEngineException(statusMsg: String)
    extends ProtocolEngineException(statusMsg)

  trait MsgBase {
    def validate(): Unit = {}
  }


  val UNINITIALIZED = "UNINITIALIZED"
  val INITIALIZED = "INITIALIZED"

  //TODO can be removed when deprecated usage is removed
  val PROTOCOL_ENCAPSULATION_FIX_DATE = "2019-07-01"

  //TODO can be removed when deprecated usage is removed
  val SERVICES_DEPRECATION_DATE = "2019-07-04"


  case class TypedMsg(msg: Any, msgType: MsgType) extends TypedMsgLike
}

/**
  * marks messages as protocol control messages
  */
trait Control

case class CtlEnvelope[A <: Control](msg: A, msgId: MsgId, threadId: ThreadId) extends Control

/**
 * marks messages as protocol system messages
 */
trait SystemMsg
trait InternalSystemMsg extends SystemMsg

trait HasMsgType {
  def msgName: String
  def msgFamily: MsgFamily

  final def msgType: MsgType = msgFamily.msgType(msgName)
  final def typedMsg = TypedMsg(this, msgType)
}

//marker trait to be used by protocol states to mark it as a terminal state (completed or error etc)
// the moment protocol engine observes state change to 'TerminalState'
// it checks data retention policy and accordingly it may
// remove all previously stored segmented states.
trait TerminalState