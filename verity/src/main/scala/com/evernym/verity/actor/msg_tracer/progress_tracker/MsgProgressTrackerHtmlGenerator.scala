package com.evernym.verity.actor.msg_tracer.progress_tracker

import java.time.Instant


object MsgProgressTrackerHtmlGenerator {

  def generateRequestsInHtml(trackingId: String,
                             reqStates: RecordedStates,
                             includeDetail: Boolean): String = {

    def generateTableRows: String = {

      def generateDataFromEvents(events: Option[List[MsgEvent]],
                                 addMsgType: Boolean = true): String = {
        events match {
          case Some(mes) if mes.nonEmpty =>
            mes.map { me =>
              val msgIdStr = me.msgId.map(mi => "msg-id: " + mi + "<br>").getOrElse("")
              val msgTypeStr = if (addMsgType) {
                me.msgType.map(mt => "msg-type: " + mt + "<br>").getOrElse("")
              } else ""
              val msgDetailStr = me.detail.map(md => "status: " + md + "<br>").getOrElse("")
              val recordedAtStr = "recorded-at: " + me.recordedAt.toString
              msgIdStr + msgTypeStr + msgDetailStr + recordedAtStr

            }.mkString("<br><br>")

          case _ => "n/a"
        }
      }

      def generateColText(colIndex: Int,
                          rs: RequestState): String =
        colIndex match {
          case 1 =>
            val outMsgIds = rs.outMsgEvents.map(_.flatMap(_.msgId)).getOrElse(List.empty)
            val dlvEvents = rs.outMsgDeliveryEvents.getOrElse(List.empty)

            val anyDlvFailed = dlvEvents.exists(me => me.toString.contains("FAILED"))
            val anyDlvSent = dlvEvents.exists(me => me.toString.contains("SENT"))
            val syncRespSent = rs.outMsgEvents.exists(oml => oml.exists(om => om.toString.contains("SENT")))
            val localSigMsgSent = rs.outMsgEvents.exists(oml => oml.exists(om => om.toString.contains("to-be-handled-locally")))
            val reqDetail = if (includeDetail) rs.toString.replace("\n", "\\n") else "n/a"
            val color =
              if (outMsgIds.isEmpty) "black"
              else if (anyDlvFailed) "red"
              else if (anyDlvSent || syncRespSent || localSigMsgSent) "green"
              else "yellow"

            s"""
              <svg height="40" width="40">
                <circle cx="24" cy="22" r="7" stroke="black" stroke-width="2" fill="$color" onclick="alert('$reqDetail')" />
              </svg>
              """
          case 2 =>
            rs.routingEvents.flatMap(_.headOption) match {
              case Some(re) =>
                "req-id:" + rs.reqId + "<br>" +
                  re.detail.map(_.replace("\n","<br>")).getOrElse("") + "<br>" +
                  s"recorded-at: ${re.recordedAt.toString}"
              case None => "n/a"
            }
          case 3 => generateDataFromEvents(rs.inMsgEvents)
          case 4 => generateDataFromEvents(rs.outMsgEvents)
          case 5 => generateDataFromEvents(rs.outMsgDeliveryEvents, addMsgType = false)
      }

      reqStates.requestStates.map { rs =>

          s"""
            <tr>
              <td class="status">${generateColText(1, rs)}</td>
              <td class="routing">${generateColText(2, rs)}</td>
              <td class="inmsg">${generateColText(3, rs)}</td>
              <td class="outmsg">${generateColText(4, rs)}</td>
              <td class="msgdlv">${generateColText(5, rs)}</td>
            </tr>
          """
        }.mkString("\n")
      }


    def generateTableHeader: String =
      """
       <tr>
         <th class="status">Status</th>
         <th class="routing">RoutingDetail</th>
         <th class="inmsg">IncomingMsgs</th>
         <th class="outmsg">OutgoingMsgs</th>
         <th class="msgdlv">OutgoingMsgs<br>Delivery Status (Async)</th>
       </tr>
        """

    s"""
      <style type="text/css">
        .tftable {font-size:12px;color:#333333;width:100%;border-width: 1px;border-color: #729ea5;border-collapse: collapse;}
        .tftable th {font-size:12px;background-color:#acc8cc;border-width: 1px;padding: 8px;border-style: solid;border-color: #729ea5;text-align:left;}
        .tftable tr {background-color:#d4e3e5;}
        .tftable td {font-size:12px;border-width: 1px;padding: 8px;border-style: solid;border-color: #729ea5;}
        .tftable tr:hover {background-color:#ffffff;}

        .status {width:3%}
        .routing {width:22%}
        .inmsg {width:25%}
        .outmsg {width:25%}
        .msgdlv {width:25%}

        p {
          background-color: #bb887e;
        }
      </style>

      <p align="center">
        Page loaded at: ${Instant.now()}
      </p>

      <table class="tftable" border="1">
        $generateTableHeader
        $generateTableRows
      </table>
    """
  }

}
