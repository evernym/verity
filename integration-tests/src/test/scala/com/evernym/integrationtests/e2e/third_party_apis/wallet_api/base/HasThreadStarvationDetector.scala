package com.evernym.integrationtests.e2e.third_party_apis.wallet_api.base

import akka.event.Logging
import com.evernym.verity.actor.testkit.ActorSpec

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait HasThreadStarvationDetector { this: ActorSpec =>

  ////NOTE: enable this if lightbend thread starvation detector dependency is integrated
//  def checkThreadStarvationFor(implicit ec: ExecutionContext): Unit = {
//    com.lightbend.akka.diagnostics.StarvationDetector.checkExecutionContext(
//      ec,
//      Logging(system, getClass),
//      com.lightbend.akka.diagnostics.StarvationDetectorSettings(3.seconds, 5.seconds, 5.seconds, 5.seconds),
//      () => system.whenTerminated.isCompleted
//    )
//  }
}
