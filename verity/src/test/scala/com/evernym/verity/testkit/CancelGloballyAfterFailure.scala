package com.evernym.verity.testkit

import org.scalatest.{Canceled, Failed, OneInstancePerTest, Outcome, Suite, TestSuite}


trait CancelGloballyAfterFailure extends TestSuite {
  import CancelGloballyAfterFailure._

  abstract override def withFixture(test: NoArgTest): Outcome = {
    if (cancelRemaining)
      Canceled("Canceled by CancelGloballyAfterFailure because a test failed previously")
    else
      super.withFixture(test) match {
        case failed: Failed =>
          cancelRemaining = true    //set to false if you don't want to stop all test case when any one of them fails
          failed
        case outcome => outcome
      }
  }

  final def newInstance: Suite with OneInstancePerTest = throw new UnsupportedOperationException
}

object CancelGloballyAfterFailure {
  @volatile var cancelRemaining = false
}