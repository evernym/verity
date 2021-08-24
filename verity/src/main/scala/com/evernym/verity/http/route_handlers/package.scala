package com.evernym.verity.http

import akka.http.scaladsl.model.{HttpMethod, StatusCode}
import com.evernym.verity.constants.Constants.CLIENT_IP_ADDRESS
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.observability.logs.AgentIdentityLoggerWrapper
import com.evernym.verity.protocol.engine.DomainId
import com.evernym.verity.util.ReqMsgContext

package object route_handlers {
  type HttpRouteWithPlatform = HttpRouteBase with PlatformServiceProvider
}

package object LoggingRouteUtil {
  private def toTuple(key:String)(value: String) = (key, value)

  private def makeTuples(domainId: Option[DomainId],
                         outboundEventType: Option[String] = None)
                        (implicit reqMsgContext: ReqMsgContext) = {
    Seq(
      Option(reqMsgContext.id).map(toTuple("request_id")),
      reqMsgContext.clientReqId.map(toTuple("client_request_id")),
      domainId.map(toTuple(AgentIdentityLoggerWrapper.DomainIdFieldName)),
      reqMsgContext.clientIpAddress.map(toTuple(CLIENT_IP_ADDRESS)),
      outboundEventType.map(toTuple("outbound_event_type"))
    ).flatten.toArray
  }

  def incomingLogMsg(target: String,
                     method: HttpMethod,
                     domainId: Option[DomainId],
                     outboundEventType: Option[String] = None)
                    (implicit reqMsgContext: ReqMsgContext): (String, Array[(String, String)]) = {
    val args = makeTuples(domainId, outboundEventType)

    (s"[incoming request] [${method.value}] on $target", args)
  }

  def outgoingLogMsg(target: String,
                     status: StatusCode,
                     domainId: Option[DomainId],
                     outboundEventType: Option[String] = None)
                    (implicit reqMsgContext: ReqMsgContext): (String, Array[(String, String)]) = {
    val args = makeTuples(domainId, outboundEventType)

    (s"[outgoing response] [$status] on $target", args)
  }
}