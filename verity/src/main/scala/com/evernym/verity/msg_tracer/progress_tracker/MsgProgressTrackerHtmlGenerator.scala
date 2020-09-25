package com.evernym.verity.msg_tracer.progress_tracker

import java.time.Instant

import com.evernym.verity.actor.msg_tracer.progress_tracker.{RecordedRequests, State}

object MsgProgressTrackerHtmlGenerator {

  def generateRequestsInHtml(trackingId: String, rr: RecordedRequests, pinstIdLinkDetail: Option[PinstIdLinkDetail] = None): String = {

    def generateTableRows: String = {

      def generateColText(r: State, colIndex: Int): String = colIndex match {
        case 1 =>
          val color =
            if (r.finishedAt.isDefined && r.failedAt.isEmpty) "green"
            else if (r.failedAt.isDefined && r.finishedAt.isEmpty) "red"
            else "yellow"
          s"""
            <svg height="40" width="40">
              <circle cx="23" cy="22" r="7" stroke="black" stroke-width="2" fill="$color" />
            </svg>
            """
        case 2 =>
          "req-id:" + r.reqId +
            r.clientIpAddress.map(ip => s"<br>from-ip-address: $ip").getOrElse("") +
            s"<br>at: ${r.receivedAt.toString}"
        case 3 =>
          r.summary.trackingParam.domainTrackingId.map(dti => s"domainTrackingId: $dti").getOrElse("") +
            r.summary.trackingParam.relTrackingId.map(rid => s"<br>relTrackingId: $rid").getOrElse("") +
            r.summary.trackingParam.threadId.map(tid => s"<br>threadId: $tid").getOrElse("")
        case 4 =>
          r.summary.inMsgParam.msgId.map(mid => s"msg-id: $mid").getOrElse("") +
            r.summary.inMsgParam.msgName.map(mn => s"<br>msg-name: $mn").getOrElse("") +
            r.summary.inMsgParam.msgType.map(mt => s"<br>msg-type: $mt").getOrElse("")
        case 5 =>
          r.summary.outMsgParam.msgId.map(mid => s"msg-id: $mid").getOrElse("") +
            r.summary.outMsgParam.msgName.map(mn => s"<br>msg-name: $mn").getOrElse("") +
            r.summary.outMsgParam.msgType.map(mt => s"<br>msg-type: $mt").getOrElse("") +
            r.summary.outMsgParam.replyToMsgId.map(rtmid => s"<br>reply-to-in-msg-id: $rtmid").getOrElse("")
        case 6 =>
          pinstIdLinkDetail match {
            case Some(pld) =>
              val pinstIdLine = if (r.summary.protoParam.pinstId.contains(trackingId))
                r.summary.protoParam.pinstId.map(pid => s"""pinst-id: $pid""").getOrElse("")
              else
                r.summary.protoParam.pinstId.map(pid => s"""<a href="${pld.pathPrefix + "/" + pid + pld.pathSuffix}">pinst-id: $pid</a>""").getOrElse("")

              pinstIdLine +
              r.summary.protoParam.familyName.map(fn => s"<br>name: $fn").getOrElse("") +
              r.summary.protoParam.familyVersion.map(fv => s"<br>version: $fv").getOrElse("")
            case _  => ""
          }
        case 7 =>
          "status: " + r.summary.status.getOrElse("n/a") +
            r.finishedAt.map(fa => s"<br>finished-at: $fa").getOrElse("")
        case 8 =>
          r.events match {
            case Some(events) if events.size > 1 =>
              val eventDetail = events
                .reverse    //to get correct index
                .zipWithIndex
                .reverse    //back to original order
                .map{ case (ew, idx) => idx + ". " + ew.context + ew.timeTakenInMillis.map(m => s" [$m millis]").getOrElse("") }
                .mkString("\n")
              val totalTimeTaken = events.flatMap(_.timeTakenInMillis).sum
              s"""
                <div title="$eventDetail">total-time: $totalTimeTaken (in millis)</div>
              """
            case _ =>
              s"""
                <div>n/a</div>
              """
          }
      }

      rr.requests.map { r =>
        s"""
          <tr>
            <td>${generateColText(r, 1)}</td>
            <td>${generateColText(r, 2)}</td>
            <td>${generateColText(r, 3)}</td>
            <td>${generateColText(r, 4)}</td>
            <td>${generateColText(r, 5)}</td>
            <td>${generateColText(r, 6)}</td>
            <td>${generateColText(r, 7)}</td>
            <td>${generateColText(r, 8)}</td>
          </tr>
        """
      }.mkString("\n")
    }

    def generateTableHeader: String =
      """
       <tr>
         <th>Status</th>
         <th>Request detail</th>
         <th>Tracking detail</th>
         <th>Incoming msg detail<br>(Control/Protocol)</th>
         <th>Outgoing msg detail<br>(Signal/Protocol)</th>
         <th>Protocol detail</th>
         <th>Msg progress status</th>
         <th>Summary<br>(mouse hover for detail)</th>
       </tr>
        """

    s"""
      <style type="text/css">
        .tftable {font-size:12px;color:#333333;width:100%;border-width: 1px;border-color: #729ea5;border-collapse: collapse;}
        .tftable th {font-size:12px;background-color:#acc8cc;border-width: 1px;padding: 8px;border-style: solid;border-color: #729ea5;text-align:left;}
        .tftable tr {background-color:#d4e3e5;}
        .tftable td {font-size:12px;border-width: 1px;padding: 8px;border-style: solid;border-color: #729ea5;}
        .tftable tr:hover {background-color:#ffffff;}

        p {
          background-color: #bb887e;
        }
      </style>

      <p align="center">
        Page loaded at: ${Instant.now()}, tracking will be expired at: ${rr.expiryTime}
      </p>

      <script>
        function performPinstLink(pinstId, dirPath: String) {
          var result = pinstId.link("file://" + dirPath);
          document.getElementById(pinstId).innerHTML = result;
        }
      </script>


      <table class="tftable" border="1">
        $generateTableHeader
        $generateTableRows
      </table>
    """
  }
}

case class PinstIdLinkDetail(pathPrefix: String, pathSuffix: String="")