package com.evernym.verity.actor.testkit.checks

import com.evernym.verity.testkit.BasicSpecBase
import org.scalatest.{AsyncTestSuite, FutureOutcome, Outcome, TestSuite}

/**
  * To be implemented by specific checkers without Scalatest.
  * The separation of the core logic in Checks and the Scalatest
  * specific functionality in ChecksForTestSuite allows for
  * easier testing of the core logic.
  */
trait Checker {

  /**
    * Type of object used to hold context between setup, check, and teardown
    */
  type Context
  type Issue
  type Issues = Vector[Issue]

  def ignoreTagNames: Set[String]

  def setup(): Context
  def check(ctx: Context): Unit
  def teardown(ctx: Context): Unit

  /** Override this method if setup and teardown are
    * not sufficient, and you need to wrap the
    * execution of the test with some other logic.
    *
    * Default is an identity function (returns the
    * input parameter).
    *
    */
  def wrap[T](ctx: Context)(test: => T): T = test

  def withCheck[T](test: () => T): () => T = () => {
    val ctx = setup()
    try {
      val result = wrap(ctx) {
        test()
      }
      check(ctx)
      result
    } finally {
      teardown(ctx)
    }
  }

}

/**
  * To be implemented by specific Checks traits for Scalatest
  */
trait ChecksForTestSuite extends ChecksForTestSuiteBase with HasCheckerRegistry {
  this: TestSuite with BasicSpecBase =>

  abstract override protected def withFixture(test: NoArgTest): Outcome = {
    try {
      reg.run(test.tags) {
        super.withFixture(test)
      }
    } catch {
      case e: FailedCheckException => fail(e.details)
    }
  }

}


/** This is needed to allow for withFixture to be
  * overridden without making ChecksForTestSuite
  * extend TestSuite.
  */
trait ChecksForTestSuiteBase { this: TestSuite =>
  protected def withFixture(test: NoArgTest): Outcome
}


/**
  * To be implemented by specific Checks traits for Scalatest
  */
trait ChecksForAsyncTestSuite extends ChecksForAsyncTestSuiteBase with HasCheckerRegistry {
  this: AsyncTestSuite with BasicSpecBase =>

  abstract override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    try {
      reg.run(test.tags) {
        super.withFixture(test)
      }
    } catch {
      case e: FailedCheckException => fail(e.details)
    }
  }

}


/** This is needed to allow for withFixture to be
  * overridden without making ChecksForTestSuite
  * extend TestSuite.
  */
trait ChecksForAsyncTestSuiteBase { this: AsyncTestSuite =>
  def withFixture(test: NoArgAsyncTest): FutureOutcome
}


sealed trait HasCheckerRegistry {
  private[checks] val reg = new CheckerRegistry()
  val registerChecker: Checker => Unit = reg.register
}


/**
  * Holds Checkers and composes them around supplied blocks of code.
  */
class CheckerRegistry(checker: Checker*) {

  private var checkers: Vector[Checker] = Vector.empty

  checker foreach register

  def register(c: Checker): Unit= {
    checkers = checkers :+ c
  }

  /**
    * Executes the supplied lambda with all registered checkers
    * @param block the lambda to be checked
    * @tparam A return type of the lambda
    * @return the result of the lambda
    */
  def run[A](block: => A): A = compose(Set.empty)(() => block)()

  /**
    * Executes the supplied lambda with all registered checkers
    * @param tags ignore tags
    * @param block the lambda to be checked
    * @tparam A return type of the lambda
    * @return the result of the lambda
    */
  def run[A](tags: Set[String])(block: => A): A = compose(tags)(() => block)()

  /**
    * Constructs a function that is a composition of all registered checkers.
    */
  private def compose[A](tags: Set[String])(block: () => A): () => A = {
    checkers
      .filter(c => tags.intersect(c.ignoreTagNames).isEmpty)
      .foldLeft(block)((a, b) => b.withCheck(a) )
  }
}

abstract class CheckException(val description: String) extends RuntimeException(description)

final class FailedCheckException(val clue: String, val events: Vector[_]) extends CheckException(clue) {
  lazy val details: String = events.mkString(s"$clue (${events.size}):\n    ", "\n    ", "")
  override def toString: String = details
}

class CheckPreconditionException(description: String) extends CheckException(description)

import akka.testkit._

trait ChecksAll extends ChecksConsole with ChecksLogs with ChecksAkkaEvents {
  this: TestSuite with TestKitBase with BasicSpecBase =>
}
