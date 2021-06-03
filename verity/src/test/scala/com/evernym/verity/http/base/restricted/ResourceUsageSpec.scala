package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.util.ByteString
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsages
import com.evernym.verity.http.base.EdgeEndpointBaseSpec

import java.net.InetAddress

trait ResourceUsageSpec { this : EdgeEndpointBaseSpec =>

  def testResourceUsage(): Unit = {
    val resource = "agent-provisioning/CONNECT"
    val localhost: InetAddress = InetAddress.getLocalHost
    val localIpAddress: String = localhost.getHostAddress
    val bucket = 300
    var usageCountBefore = 0
    val usageCountAfter: Int = 8
    val payload = ByteString(s"""{"resourceUsageCounters": [{"resourceName\": "$resource", "bucketId": $bucket, "newCount": $usageCountAfter}]}""")
    val blockEntityPayload = ByteString(s"""{"msgType": "block"}""")
    val clearEntityBlockingPayload = ByteString(s"""{"msgType": "block", "period": 0, "allResources": "Y"}""")
    val warnResourcePayload = ByteString(s"""{"msgType": "warn"}""")
    val clearResourceWarningPayload = ByteString(s"""{"msgType": "warn", "period": 0}""")

    "when sent get resource usage for given id" - {
      "should get resource usages, and confirm count for agent-provisioning/CONNECT msg usage is 2" in {
        buildGetReq(s"/agency/internal/resource-usage/id/$localIpAddress") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          usageCountBefore = resourceUsages.usages("agent-provisioning/CONNECT")("300").usedCount
        }
      }
    }

    "when sent update counter for agent-provisioning/CONNECT msg usage" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/counter",
          HttpEntity.Strict(ContentTypes.`application/json`, payload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    "when sent get resource usage for given id after usage updated" - {
      "should get resource usages, and confirm count for agent-provisioning/CONNECT msg usage is 3" in {
        buildGetReq(s"/agency/internal/resource-usage/id/$localIpAddress") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          resourceUsages.usages("agent-provisioning/CONNECT")("300").usedCount shouldBe usageCountAfter
        }
      }
    }

    "when sent get resource usage for global" - {
      "should respond with resource usages" in {
        buildGetReq(s"/agency/internal/resource-usage/id/global") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          resourceUsages.usages("agent-provisioning/CONNECT")("300").usedCount shouldBe usageCountBefore
        }
      }
    }

    "when IP address is blocked" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, blockEntityPayload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
      "should be able to get active blocks, and confirm the IP address is blocked" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val usageBlockingStatus = responseTo[Map[String, Any]]
          usageBlockingStatus.contains(localIpAddress) shouldBe true
          val entityBlockingStatus = usageBlockingStatus(localIpAddress).asInstanceOf[Map[String, Any]]
          val entityBlockingDetail = entityBlockingStatus("status").asInstanceOf[Map[String, String]]
          entityBlockingDetail.contains("blockFrom") shouldBe true
          entityBlockingDetail.contains("blockTill") shouldBe false
        }
      }
      "should be able clear block" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, clearEntityBlockingPayload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
      "should be able to get active blocks again, and confirm the IP address is no longer blocked" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val usageBlockingStatus = responseTo[Map[String, Any]]
          usageBlockingStatus.contains(localIpAddress) shouldBe false
        }
      }
    }

    "when resource is warned for IP address" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/resource/$resource",
          HttpEntity.Strict(ContentTypes.`application/json`, warnResourcePayload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
      "should be able to get active warnings, and confirm the resource is warned for the IP address" in {
        buildGetReq(s"/agency/internal/resource-usage/warned") ~> epRoutes ~> check {
          status shouldBe OK
          val usageWarningStatus = responseTo[Map[String, Any]]
          usageWarningStatus.contains(localIpAddress) shouldBe true
          val entityWarningStatus = usageWarningStatus(localIpAddress).asInstanceOf[Map[String, Any]]
          val resourcesWarningStatus = entityWarningStatus("resourcesStatus").asInstanceOf[Map[String, Any]]
          val resourceWarningDetail = resourcesWarningStatus(resource).asInstanceOf[Map[String, String]]
          resourceWarningDetail.contains("warnFrom") shouldBe true
          resourceWarningDetail.contains("warnTill") shouldBe false
        }
      }
      "should be able clear warning" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/resource/$resource",
          HttpEntity.Strict(ContentTypes.`application/json`, clearResourceWarningPayload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
      "should be able to get active warnings again, and confirm the resource is no longer warned for IP address" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val usageWarningStatus = responseTo[Map[String, Any]]
          // localIpAddress should be absent in the map because no resources should be warned for it
          usageWarningStatus.contains(localIpAddress) shouldBe false
        }
      }
    }
  }

}
