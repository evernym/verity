package com.evernym.integrationtests.e2e.flow

import com.evernym.integrationtests.e2e.scenario.ApplicationAdminExt
import com.evernym.integrationtests.e2e.util.ReportDumpUtil
import com.evernym.verity.actor.msg_tracer.progress_tracker.RecordedRequests
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.msg_tracer.progress_tracker.{MsgProgressTrackerHtmlGenerator, PinstIdLinkDetail}
import com.evernym.verity.testkit.BasicSpec

import scala.sys.process._


trait MessageTrackingFlow {
  this: BasicSpec =>

  def testMessageTracking(aae: ApplicationAdminExt): Unit = {
    if(aae.instance.isRunningLocally){
      s"when sent get message tracking api call (${aae.name})" - {
        val context = "MsgProgressTracker"
        val dumpDirPath = ReportDumpUtil.dumpFilePath(aae, context)
        val msgProgressTrackingResultFilename="msg-progress-tracking-result"

        "should be able to fetch message tracking data" in {
          val trackingId = "global"
          val jsonResult = aae.getMessageTrackingData(trackingId)
          val recordedRequests = DefaultMsgCodec.fromJson[RecordedRequests](jsonResult)
          val htmlResult = MsgProgressTrackerHtmlGenerator.generateRequestsInHtml(
            trackingId, recordedRequests, Option(PinstIdLinkDetail("file://" + dumpDirPath.toString, ".html")))

          recordedRequests.requests.foreach { r =>
            r.summary.protoParam.pinstId.foreach { pid =>
              val protoHtmlResult = aae.getMessageTrackingData(id = pid, inHtml = "Y")
              val protoJsonResult = aae.getMessageTrackingData(id = pid)
              ReportDumpUtil.dumpData(context, protoHtmlResult, s"$pid.html", aae, printDumpDetail = false)
              ReportDumpUtil.dumpData(context, protoJsonResult, s"$pid.json", aae, printDumpDetail = false)
            }
          }
          ReportDumpUtil.dumpData(context, jsonResult, s"$msgProgressTrackingResultFilename.json", aae, printDumpDetail = false)
          ReportDumpUtil.dumpData(context, htmlResult, s"$msgProgressTrackingResultFilename.html", aae, printDumpDetail = false)
        }
      }
    }
  }

  def testMessageTrackingMetrics(aae: ApplicationAdminExt): Unit = {
    if(aae.instance.isRunningLocally) {
      s"when processing message tracking metrics" - {
        val context = "MsgProgressTracker"
        val reportDir = aae.scenario.testDir.toString + "/" + context

        "should be able to produce csv and json reports" in {
          val formats = List("csv", "json")
          formats.foreach { format =>
            val (out, err) = (new StringBuffer(), new StringBuffer())
            val result = aae.scenario.projectDir.resolve("integration-tests/src/test/msgProgressTrackingReport.py")
              .toString + s" --verbose --$format -d $reportDir " +
              s"-o $reportDir/msg-progress-tracking-report.$format" ! ProcessLogger( out append _, err append _ )
            out.toString shouldBe ""
            err.toString shouldBe ""
            result shouldBe 0
          }
        }

        /*"collected protocol benchmarks should not be slower within a given tolerance" in {
          TODO: get the json version of the report and keep a copy under version control as a baseline
          TODO: test the report generated above to the version of the report under version control and fail tests if
                timeTakenMillis are slower by a configurable threshold (i.e. 50 millis)
        }*/
      }
    }
  }
}
