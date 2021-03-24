package com.evernym.verity.actor.msg_tracer.progress_tracker

import java.time.temporal.ChronoUnit
import java.time.Instant


object MsgProgressTrackerHtmlGenerator {

  def generateRequestsInHtml(reqStates: RecordedStates,
                             includeDetail: Boolean): String = {

    val expiryTimeInMinutes = ChronoUnit.MINUTES.between(Instant.now, reqStates.trackingExpiresAt)

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

            val anyFailedDlv = dlvEvents.exists(me => me.toString.contains("FAILED"))
            val anySuccessfulDlv = dlvEvents.exists(me => me.toString.contains("SENT") || me.toString.contains("SUCCESSFUL"))
            val anySyncRespSent = rs.outMsgEvents.exists(oml => oml.exists(om => om.toString.contains("SENT")))
            val localSigMsgSent = rs.outMsgEvents.exists(oml => oml.exists(om => om.toString.contains("to-be-handled-locally")))
            val reqDetail =
              if (includeDetail) rs.toString.replace("\n", "\\n")
              else "include `withDetail=Y` query param to see detail"
            val color =
              if (outMsgIds.isEmpty) "black"
              else if (anyFailedDlv) "red"
              else if (anySuccessfulDlv || anySyncRespSent || localSigMsgSent) "green"
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
      s"""
       <tr>
          <th class="heading" colspan="5"> Page loaded at: ${Instant.now()}, tracking expires in minutes: $expiryTimeInMinutes </th>
       </tr>
       <tr>
         <th class="status">Status</th>
         <th class="routing">RequestDetail</th>
         <th class="inmsg">IncomingMsgs</th>
         <th class="outmsg">OutgoingMsgs</th>
         <th class="msgdlv">OutgoingMsgs<br>Delivery Status</th>
       </tr>
      """

    s"""
      <style type="text/css">

        .tftable {
          font-size:12px;
          color:#333333;
          width:100%;
          border-width: 1px;
          border-color: #729ea5;
          #border-collapse: collapse;
          position: relative;
        }

        .tftable tr {
          background-color:#d4e3e5;
        }

        .tftable tr:hover {
          background-color:#ffffff;
        }

        .tftable th {
          position: sticky;
          top: 0;
          padding: 8px;
          font-size: 12px;
          background-color: #ac938e ;
          border-style: solid;
          border-color: black;
          text-align: center;
        }

        .tftable td {
          padding: 8px;
          font-size: 12px;
          border-width: 1px;
          border-style: solid;
          border-color: #729ea5;
        }

        .heading {
          background-color: #acc8cc !important;
          width: 100%;
          text-align: center;
        }

        .status {
          width: 3%
        }
        .routing {
          width: 24%
        }
        .inmsg {
          width: 24%
        }
        .outmsg {
          width: 24%
        }
        .msgdlv {
          width: 25%
        }

        p {
          background-color: #bb887e;
        }
      </style>

      <table class="tftable" border="1">
        $generateTableHeader
        $generateTableRows
      </table>
    """
  }

}
