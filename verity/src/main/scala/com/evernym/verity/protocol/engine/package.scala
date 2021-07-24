package com.evernym.verity.protocol

import com.evernym.verity.protocol.engine._

package object engine {

  type PinstId = String //Protocol Instance Id

  type ParameterName = String
  type ParameterValue = String

  type DID = String
  type DIDKeyStr = String
  type VerKey = String
  type Ledgers = List[Map[String, Any]]

  type MsgFamilyName = String
  type MsgFamilyVersion = String
  type MsgName = String
  type MsgTypeStr = String  //refactor this name to something better (we have a case class called MsgType and this was colliding with it)

  type ContextId = String
  type RelationshipId = String
  type DomainId = String
  type StorageId = String

  type ThreadId = String
  type MsgId = String
  type RefMsgId = String
  type SafeThreadId = String

  type Nonce = String

  sealed trait MsgFamilyQualifier

  case object EvernymQualifier extends MsgFamilyQualifier

  case object CommunityQualifier extends MsgFamilyQualifier

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


  val UNINITIALIZED = "UNINITIALIZED"
  val INITIALIZED = "INITIALIZED"

  //TODO can be removed when deprecated usage is removed
  val PROTOCOL_ENCAPSULATION_FIX_DATE = "2019-07-01"

  //TODO can be removed when deprecated usage is removed
  val SERVICES_DEPRECATION_DATE = "2019-07-04"

  trait MsgTypeLike {
    def familyName: MsgFamilyName
    def familyVersion: MsgFamilyVersion
    def msgName: MsgName
    lazy val protoRef: ProtoRef = ProtoRef(familyName, familyVersion)
  }

  case class MsgType(familyQualifier: MsgFamilyQualifier,
                     familyName: MsgFamilyName,
                     familyVersion: MsgFamilyVersion,
                     msgName: MsgName
                    ) extends MsgTypeLike {

    def normalizedMsgType: MsgType = {
      def normalized(input: String): String = {
        //here are PROPOSED (not accepted) naming conventions mentioned here:
        // about protocol/message names
        //  https://github.com/hyperledger/aries-rfcs/blob/f7e657858c2103c68d14aac75a8938a5bd3eea1e/concepts/0074-didcomm-best-practices/README.md#snake_case-and-variants
        // if that gets accepted, we may wanna use this normalized function applied to protocol name and message name
        input
      }
      MsgType(familyQualifier, normalized(familyName), familyVersion, normalized(msgName))
    }

    override def toString = s"$msgName (${protoRef.toString})"
  }

  trait TypedMsgLike {
    def msg: Any
    def msgType: MsgType
  }

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
