package com.evernym.verity.actor.agent.msgrouter

import com.evernym.verity.actor.LegacyRouteSet
import com.evernym.verity.actor.agent.msgrouter.legacy.{GetRouteBatch, OrderedRoutes}
import com.evernym.verity.testkit.BasicSpec

import scala.collection.immutable

class OrderedRoutesRandomSpec
  extends BasicSpec {

  val orderedRoutes1 = new OrderedRoutes()
  val orderedRoutes2 = new OrderedRoutes()

  "OrderedRoutes" - {
    "when checked for random GetRouteBatch" - {
      "should return expected result" in {
        List(orderedRoutes1, orderedRoutes2).foreach { orderedRoutes =>
          orderedRoutes.routes.size shouldBe 0
          routesToBeAdded.zipWithIndex.foreach { case (lrs, index) =>
            orderedRoutes.add(index, lrs)
          }

          orderedRoutes.routes.size shouldBe routesToBeAdded.size

          checkOrder(orderedRoutes, GetRouteBatch(-1, 1), List.empty)

          checkOrder(orderedRoutes, GetRouteBatch(0,  1), List("JcCRPibqybAUbpMAanuc4Q"))
          checkOrder(orderedRoutes, GetRouteBatch(0,  2), List("JcCRPibqybAUbpMAanuc4Q", "8s66PTpQ92F9qC1cToRdqa"))

          checkOrder(orderedRoutes, GetRouteBatch(2,  1), List("Kn38YK23TSQT29Er3ZXCMC"))
          checkOrder(orderedRoutes, GetRouteBatch(2,  2), List("Kn38YK23TSQT29Er3ZXCMC", "3bEaWn6JJtQ4foQNu6vXEj"))
          checkOrder(orderedRoutes, GetRouteBatch(2,  3), List("Kn38YK23TSQT29Er3ZXCMC", "3bEaWn6JJtQ4foQNu6vXEj", "PbTviLRjLetCpfvZ9Sr3Da"))

          checkOrder(orderedRoutes, GetRouteBatch(3,  1), List("3bEaWn6JJtQ4foQNu6vXEj"))
          checkOrder(orderedRoutes, GetRouteBatch(3,  2), List("3bEaWn6JJtQ4foQNu6vXEj", "PbTviLRjLetCpfvZ9Sr3Da"))

          checkOrder(orderedRoutes, GetRouteBatch(15, 1), List("7ae6xunGm5Le99HYPFkdXk"))
          checkOrder(orderedRoutes, GetRouteBatch(23, 1), List("NhQzXftKvNcoEjhFuXgSR3"))

          checkOrder(orderedRoutes, GetRouteBatch(24, 1), List.empty)
        }
      }
    }
  }

  private def checkOrder(orderedRoutes: OrderedRoutes, grb: GetRouteBatch, expected: List[String]): Unit = {
    orderedRoutes.getRouteBatch(grb) shouldBe expected
  }

  private val routesToBeAdded: immutable.Seq[LegacyRouteSet] = List(
    LegacyRouteSet("JcCRPibqybAUbpMAanuc4Q",4,"6b91abe0-9b6b-4bd6-8ebd-fba8831bdd02"),
    LegacyRouteSet("8s66PTpQ92F9qC1cToRdqa",3,"689cf0ac-f6b4-46eb-ba34-67fae8dcf33a"),
    LegacyRouteSet("Kn38YK23TSQT29Er3ZXCMC",2,"38ec0996-1b79-4e20-a343-0d7a2cf6e8d6"),
    LegacyRouteSet("3bEaWn6JJtQ4foQNu6vXEj",3,"4453446a-700e-441c-ba07-12c5d170a102"),
    LegacyRouteSet("PbTviLRjLetCpfvZ9Sr3Da",4,"1837417b-7c88-49e1-a14b-a2ca013ca6ae"),
    LegacyRouteSet("DKxSjupHj7XjSLeDgHTQf7",3,"83401a02-2ff0-4593-bef6-8f8c9e609c30"),
    LegacyRouteSet("2mcvrCt3UruZZi87ksUdyQ",3,"4346b8d5-b69e-4fad-819c-cf62ec525d7d"),
    LegacyRouteSet("RuZCdezz1svq6N3FRFEsBP",2,"da0d6e5f-604a-401b-b59f-5a5f7faae429"),
    LegacyRouteSet("NjepX32dXaGDQi8cJGsxDH",3,"2acb034c-1413-472d-aaf2-149b1b68c94b"),
    LegacyRouteSet("8Fj2KiHjFuHR65GXNDFTu5",3,"499e413c-6fb6-4baf-a3c5-2303a252452e"),
    LegacyRouteSet("Y4QrVxhftKQCeZ6LPW17ES",2,"f2ee287e-9dbc-4f17-812a-d63c20296d1e"),
    LegacyRouteSet("TAB8RfQTXHMZDJZs83gD27",2,"1ab2f14c-4709-4382-a5bc-ad30f1246490"),
    LegacyRouteSet("3HHQta1xG3hDRuvnaAipCf",2,"3cb42ef4-4977-41a8-a78a-c1cfa2f5b5d7"),
    LegacyRouteSet("DgpTBo2xdponJMYrQePxkU",3,"8ce39710-9620-4f9f-a0c8-c007245b8d28"),
    LegacyRouteSet("2HfWiA1Q9hTADz5KDMA3ML",2,"630ca942-bd93-4d31-98dc-15c2fe5a2a6a"),
    LegacyRouteSet("7ae6xunGm5Le99HYPFkdXk",3,"e2321c66-8317-497b-9b8c-fceb2cde5379"),
    LegacyRouteSet("JwMNtyBtrehAfkhwVsgd8J",3,"9c04dd2f-f198-4e18-927b-c44022fa0b35"),
    LegacyRouteSet("KvdUuxq4MuSdqEaXJ89rki",3,"a5a790bd-5c11-46af-a44c-dcfaaf1caa74"),
    LegacyRouteSet("Nk1yDyAeRbTwAsx4fVnM3v",4,"aa22d577-75d7-4749-a480-80987d305b68"),
    LegacyRouteSet("NL9KtWU6njEBCKS4dcoGKA",3,"247db89e-a19d-4104-a9be-a1d9650b5601"),
    LegacyRouteSet("2kxeJXEeUm5cPH8rgS8bP5",4,"a5dacd55-9d88-4caf-af60-da1cfdddd621"),
    LegacyRouteSet("EptmZVwEhWZU3uDPVTAgRS",3,"a303c3ee-a0de-46ff-ba6c-01ba38234a81"),
    LegacyRouteSet("3LSJ8UKAqJsb5xqKz2yMTa",3,"6606a71e-ebd1-450c-954a-13265636b920"),
    LegacyRouteSet("NhQzXftKvNcoEjhFuXgSR3",4,"28dbce09-ace9-4cd6-9b82-d30687f539f3")
  )
}