package com.evernym.verity.protocol.engine

object Constants {

  val MSG_NAME = "name"
  val VER = "ver"

  val `@TYPE` = "@type"
  val `@id` = "@id"
  val THREAD = "~thread"

  //message family version
  val MFV_UNKNOWN = "unknown"
  val MFV_0_5 = "0.5"
  val MFV_0_6 = "0.6"
  val MFV_0_7 = "0.7"
  val MFV_1_0 = "1.0"
  val MFV_0_1_0 = "0.1.0"
  val MFV_0_1 = "0.1"

  //message type version (this is only used for existing/old agent messages)
  //it is the 'ver' attribute in the legacy typed json: "{"@type":{"name":"connect","ver":"1.0"}}"
  val MTV_1_0 = "1.0"

  val MSG_TYPE_CONNECT = "CONNECT"
  val MSG_TYPE_SIGN_UP = "SIGNUP"
  val MSG_TYPE_CREATE_AGENT = "CREATE_AGENT"
  val MSG_FAMILY_AGENT_PROVISIONING = "agent-provisioning"
  val MSG_FAMILY_TOKEN_PROVISIONING = "token-provisioning"
  val MSG_FAMILY_NAME_0_5 = "0.5"
}
