package com.evernym.verity.http.base.restricted

import java.net.InetAddress

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.util.ByteString
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.BlockingDetail
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsages
import com.evernym.verity.http.base.EndpointHandlerBaseSpec

trait ResourceUsageSpec { this : EndpointHandlerBaseSpec =>

  def testResourceUsage(): Unit = {
    val resource = "CONNECT"
    val localhost: InetAddress = InetAddress.getLocalHost
    val localIpAddress: String = localhost.getHostAddress
    val bucket = 300
    var usageCountBefore = 0
    val usageCountAfter: Int = 8
    val payload = ByteString(s"""{"resourceUsageCounters": [{"resourceName\":"$resource", "bucketId":$bucket, "newCount":$usageCountAfter}]}""")
    val blockPayload = ByteString(s"""{"msgType": "block"}""")
    val clearBlockPayload= ByteString(s"""{"msgType": "block", "period":0, "allResources":"Y"}""")

    "when sent get resource usage for given id" - {
      "should get resource usages, and confirm count for CONNECT msg usage is 2" in {
        buildGetReq(s"/agency/internal/resource-usage/id/$localIpAddress") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          usageCountBefore = resourceUsages.usages("CONNECT")("300").usedCount
        }
      }
    }

    "when sent update counter for CONNECT msg usage" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/counter",
          HttpEntity.Strict(ContentTypes.`application/json`, payload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    "when sent get resource usage for given id after usage updated" - {
      "should get resource usages, and confirm count for CONNECT msg usage is 3" in {
        buildGetReq(s"/agency/internal/resource-usage/id/$localIpAddress") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          resourceUsages.usages("CONNECT")("300").usedCount shouldBe usageCountAfter
        }
      }
    }

    "when sent get resource usage for global" - {
      "should respond with resource usages" in {
        buildGetReq(s"/agency/internal/resource-usage/id/global") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          resourceUsages.usages("CONNECT")("300").usedCount shouldBe usageCountBefore
        }
      }
    }

    "when IP address is blocked" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, blockPayload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
      "should be able to get resource usages, and confirm the IP is blocked" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages: Map[String, Any] = responseTo[Map[String, Any]]
          resourceUsages.contains(localIpAddress) shouldBe true
          val blockingStatusByIP: Map[String, Any] = resourceUsages(localIpAddress).asInstanceOf[Map[String, Any]]
          val blockingStatus: Map[String, BlockingDetail] = blockingStatusByIP("status").asInstanceOf[Map[String, BlockingDetail]]
          blockingStatus.contains("blockFrom") shouldBe true
          blockingStatus.contains("blockTill") shouldBe false
        }
      }
      "should be able clear block" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, clearBlockPayload)
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
      "should be able to get resource usages again, and confirm the IP is no longer blocked" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages: Map[String, Any] = responseTo[Map[String, Any]]
          resourceUsages.contains(localIpAddress) shouldBe false
        }
      }
    }
  }

}
