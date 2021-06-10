package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.util.ByteString
import com.evernym.verity.actor.resourceusagethrottling.ResourceName
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsages
import com.evernym.verity.http.base.EdgeEndpointBaseSpec

import java.net.InetAddress

trait ResourceUsageSpec { this: EdgeEndpointBaseSpec =>

  def testResourceUsage(): Unit = {

    val localhost: InetAddress = InetAddress.getLocalHost
    val localIpAddress: String = localhost.getHostAddress

    val resources: List[ResourceName] = List(
      "POST_agency_msg",
      "endpoint.all",
      "CREATE_KEY",
      "agent-provisioning/CONNECT",
      "message.all",
    )

    val bucket = "300"

    val newUsageCounts: Map[ResourceName, Int] = Map(
      "POST_agency_msg" -> 15,
      "endpoint.all" -> 20,
      "CREATE_KEY" -> 3,
      "agent-provisioning/CONNECT" -> 5,
      "message.all" -> 10,
    )

    "when sent get resource usage for source ID" - {
      "should respond with resource usages" in {
        buildGetReq(s"/agency/internal/resource-usage/id/$localIpAddress") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceUsages = responseTo[ResourceUsages]
          resources.foreach { resource =>
            resourceUsages.usages(resource)(bucket).usedCount > 0 // Only resources used earlier are used by this test
          }
        }
      }
    }

    "when sent update counter for resource usage for source ID" - {
      "should respond with OK" in {
        resources.foreach { resource =>
          buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/counter",
            HttpEntity.Strict(ContentTypes.`application/json`, ByteString(
              s"""{"resourceUsageCounters": [{"resourceName": "$resource", "bucketId": $bucket, "newCount": ${newUsageCounts(resource)}}]}"""))
          ) ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }
    }

    "when sent get resource usage for the source ID" - {
      "should respond with resource usages, and confirm count for the resource has been updated" in {
        resources.foreach { resource =>
          buildGetReq(s"/agency/internal/resource-usage/id/$localIpAddress") ~> epRoutes ~> check {
            status shouldBe OK
            val resourceUsages = responseTo[ResourceUsages]
            resourceUsages.usages(resource)(bucket).usedCount shouldBe newUsageCounts(resource)
          }
        }
      }
    }

    "when source ID is warned" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "warn"}"""))
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }

      "should be able to get active warnings, and confirm the source ID is warned" in {
        buildGetReq(s"/agency/internal/resource-usage/warned") ~> epRoutes ~> check {
          status shouldBe OK
          val usageWarningStatus = responseTo[Map[String, Any]]
          usageWarningStatus.contains(localIpAddress) shouldBe true
          val entityWarningStatus = usageWarningStatus(localIpAddress).asInstanceOf[Map[String, Any]]
          val entityWarningDetail = entityWarningStatus("status").asInstanceOf[Map[String, String]]
          entityWarningDetail.contains("warnFrom") shouldBe true
          entityWarningDetail.contains("warnTill") shouldBe false
        }
      }

      "should be able to clear the warning" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "warn", "period": 0, "allResources": "Y"}"""))
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }

      "should be able to get active warnings again, and confirm the source ID is no longer warned" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val usageWarningStatus = responseTo[Map[String, Any]]
          usageWarningStatus.contains(localIpAddress) shouldBe false
        }
      }
    }

    "when source ID is blocked" - {
      "should respond with OK" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "block"}"""))
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }

      "should be able to get active blocks, and confirm the source ID is blocked" in {
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

      "should be able to clear the block" in {
        buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "block", "period": 0, "allResources": "Y"}"""))
        ) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }

      "should be able to get active blocks again, and confirm the source ID is no longer blocked" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val usageBlockingStatus = responseTo[Map[String, Any]]
          usageBlockingStatus.contains(localIpAddress) shouldBe false
        }
      }
    }

    "when resource is warned for source ID" - {
      "should respond with OK" in {
        resources.foreach { resource =>
          buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/resource/$resource",
            HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "warn"}"""))
          ) ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }

      "should be able to get active warnings, and confirm the resource is warned for the source ID" in {
        buildGetReq(s"/agency/internal/resource-usage/warned") ~> epRoutes ~> check {
          status shouldBe OK
          val usageWarningStatus = responseTo[Map[String, Any]]
          usageWarningStatus.contains(localIpAddress) shouldBe true
          val entityWarningStatus = usageWarningStatus(localIpAddress).asInstanceOf[Map[String, Any]]
          val resourcesWarningStatus = entityWarningStatus("resourcesStatus").asInstanceOf[Map[String, Any]]
          resources.foreach { resource =>
            val resourceWarningDetail = resourcesWarningStatus(resource).asInstanceOf[Map[String, String]]
            resourceWarningDetail.contains("warnFrom") shouldBe true
            resourceWarningDetail.contains("warnTill") shouldBe false
          }
        }
      }

      "should be able to clear the warning" in {
        resources.foreach { resource =>
          buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/resource/$resource",
            HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "warn", "period": 0}"""))
          ) ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }

      "should be able to get active warnings again, and confirm the resource is no longer warned for the source ID" in {
        buildGetReq(s"/agency/internal/resource-usage/warned") ~> epRoutes ~> check {
          status shouldBe OK
          val usageWarningStatus = responseTo[Map[String, Any]]
          // localIpAddress should be absent in usageWarningStatus because no resources should be warned for it
          usageWarningStatus.contains(localIpAddress) shouldBe false
        }
      }
    }

    "when resource is blocked for source ID" - {
      "should respond with OK" in {
        resources.foreach { resource =>
          buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/resource/$resource",
            HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "block"}"""))
          ) ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }

      "should be able to get active blocks, and confirm the resource is blocked for the source ID" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val usageBlockingStatus = responseTo[Map[String, Any]]
          usageBlockingStatus.contains(localIpAddress) shouldBe true
          val entityBlockingStatus = usageBlockingStatus(localIpAddress).asInstanceOf[Map[String, Any]]
          val resourcesBlockingStatus = entityBlockingStatus("resourcesStatus").asInstanceOf[Map[String, Any]]
          resources.foreach { resource =>
            val resourceBlockingDetail = resourcesBlockingStatus(resource).asInstanceOf[Map[String, String]]
            resourceBlockingDetail.contains("blockFrom") shouldBe true
            resourceBlockingDetail.contains("blockTill") shouldBe false
          }
        }
      }

      "should be able to clear the block" in {
        resources.foreach { resource =>
          buildPutReq(s"/agency/internal/resource-usage/id/$localIpAddress/resource/$resource",
            HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"msgType": "block", "period": 0}"""))
          ) ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }

      "should be able to get active blocks again, and confirm the resource is no longer blocked for the source ID" in {
        buildGetReq(s"/agency/internal/resource-usage/blocked") ~> epRoutes ~> check {
          status shouldBe OK
          val resourceBlockingDetail = responseTo[Map[String, Any]]
          // localIpAddress should be absent in resourceBlockingDetail because no resources should be blocked for it
          resourceBlockingDetail.contains(localIpAddress) shouldBe false
        }
      }
    }

  }

}
