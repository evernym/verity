package com.evernym.verity.http.common.models

import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.MediaType.NotCompressible

object HttpCustomTypes {

  private val HTTP_MEDIA_TYPE_APPLICATION = "application"
  private val HTTP_MEDIA_SUB_TYPE_SSI_AGENT_WIRE = "ssi-agent-wire"
  private val HTTP_MEDIA_SUB_TYPE_DIDCOMM_ENVELOPE_ENC = "didcomm-envelope-enc"

  lazy val MEDIA_TYPE_SSI_AGENT_WIRE: MediaType.Binary = MediaType.customBinary(
    HTTP_MEDIA_TYPE_APPLICATION,
    HTTP_MEDIA_SUB_TYPE_SSI_AGENT_WIRE,
    NotCompressible)

  lazy val MEDIA_TYPE_DIDCOMM_ENVELOPE_ENC: MediaType.Binary = MediaType.customBinary(
    HTTP_MEDIA_TYPE_APPLICATION,
    HTTP_MEDIA_SUB_TYPE_DIDCOMM_ENVELOPE_ENC,
    NotCompressible)
}
