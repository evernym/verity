package com.evernym.verity.http.common.models

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.util2.{ActorErrorResp, Status}

//NOTE: DON'T rename any fields of this case class, it is sent in http response
//and will break the api
case class StatusDetailResp(statusCode: String, statusMsg: String, detail: Option[String]) extends ActorMessage

case object StatusDetailResp {
  def apply(sd: StatusDetail, detail: Option[Any] = None): StatusDetailResp =
    StatusDetailResp(sd.statusCode, sd.statusMsg, detail.map(_.toString))

  def apply(br: ActorErrorResp): StatusDetailResp = {
    StatusDetailResp(Status.getFromCode(br.statusCode).copy(statusMsg = br.respMsg))
  }
}