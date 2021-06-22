package com.evernym.verity.actor.agent.msgrouter

import com.evernym.verity.actor.LegacyRouteSet
import com.evernym.verity.actor.agent.msgrouter.legacy.{GetRouteBatch, OrderedRoutes}
import com.evernym.verity.testkit.BasicSpec

import scala.collection.immutable

class OrderedRoutesSequentialSpec
  extends BasicSpec {

  val orderedRoutes1 = new OrderedRoutes()
  val orderedRoutes2 = new OrderedRoutes()

  val totalCandidates = 1600

  val routesToBeAdded: immutable.Seq[LegacyRouteSet] = (0 until totalCandidates).map { i =>
    LegacyRouteSet(s"did$i", i, s"address$i")
  }

  "OrderedRoutes" - {
    "when checked for sequential GetRouteBatch" - {
      "should return expected result" in {
        List(orderedRoutes1, orderedRoutes2).foreach { orderedRoutes =>
          orderedRoutes.getAllRouteDIDs().size shouldBe 0
          routesToBeAdded.foreach(orderedRoutes.add)
          orderedRoutes.getAllRouteDIDs().size shouldBe routesToBeAdded.size
          orderedRoutes.getAllRouteDIDs() shouldBe routesToBeAdded.map(_.forDID)
          checkInsertionOrderIsMaintained(orderedRoutes)
        }
      }
    }
  }

  private def checkInsertionOrderIsMaintained(orderedRoutes: OrderedRoutes): Unit = {
    val totalCandidates = orderedRoutes.getAllRouteDIDs().size
    val mockExecutor = new MockExecutor(1)
    mockExecutor.setTotalCandidates(totalCandidates)
    while (mockExecutor.getTotalProcessed < totalCandidates) {
      val grb = mockExecutor.buildGetRouteBatch()
      val expected = {
        val from = grb.fromIndex
        val to = {
          val lastNumber = grb.fromIndex+grb.batchSize
          if (lastNumber > totalCandidates) totalCandidates
          else lastNumber
        }
        (from until to).map(e => s"did$e")
      }
      orderedRoutes.getRouteBatch(grb) shouldBe expected
      mockExecutor.incrementProcessed(expected.size)
    }
  }
}

class MockExecutor(batchSize: Int) {
  private var totalCandidates = 0
  private var totalProcessed = 0

  def getBatchSize: Int = batchSize

  def setTotalCandidates(total: Int): Unit = {
    totalCandidates = total
  }

  def incrementProcessed(by: Int = 1): Unit = {
    totalProcessed = totalProcessed + by
  }

  def getTotalProcessed: Int = totalProcessed

  def buildGetRouteBatch(): GetRouteBatch = {
    val fromIndex = totalProcessed%batchSize match {
      case 0 => totalProcessed
      case _ => (totalProcessed/batchSize)*batchSize
    }
    GetRouteBatch(totalCandidates, fromIndex, batchSize)
  }
}