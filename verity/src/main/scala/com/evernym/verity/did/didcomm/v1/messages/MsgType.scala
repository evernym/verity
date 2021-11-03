package com.evernym.verity.did.didcomm.v1.messages

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}

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

  override def toString = s"$msgName ($familyName[$familyVersion])"
}

trait TypedMsgLike {
  def msg: Any
  def msgType: MsgType
}

trait MsgTypeLike {
  def familyName: MsgFamilyName
  def familyVersion: MsgFamilyVersion
  def msgName: MsgName
//  lazy val protoRef: ProtoRef = ProtoRef(familyName, familyVersion)
}