//package com.evernym.verity

//import java9.util.concurrent.CompletableFuture
//import com.evernym.verity.testkit.BasicSpec

//
//import com.evernym.verity.CustomExecutionContext.executionContext
//import scala.concurrent.{Await, TimeoutException}
//import scala.concurrent.duration._
//
//class FutureFromJava9Test extends BasicSpec {
//
//  "A Java Future" - {
//    "can be converted totoScala" in {
//      val javaFut = new CompletableFuture[String]()
//
//      val scalaFut = FutureFromJava9.toScala(javaFut)
//
//      scalaFut.map(_ + " mapped in Scala")
//
//      intercept[TimeoutException] {
//        Await.result(scalaFut, 3 seconds)
//      }
//
//      javaFut.complete("Java Result")
//
//      val result = Await.result(scalaFut, 1 millisecond)
//
//      result shouldBe "Java Result mapped in Scala"
//    }
//  }
//}
