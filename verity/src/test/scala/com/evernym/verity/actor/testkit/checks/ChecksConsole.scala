package com.evernym.verity.actor.testkit.checks

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.testkit.BasicSpecBase
import com.evernym.verity.util.RegexHelp
import org.scalatest.{AsyncTestSuite, Tag, TestSuite}

import scala.io.Source


/** Mix in this trait to get automatic console checking
  */
trait ChecksConsole extends ChecksForTestSuite {
  this: TestSuite with BasicSpecBase =>

  registerChecker(new ConsoleChecker)
}


/** Mix in this trait to get automatic console checking
  */
trait ChecksConsoleAsync extends ChecksForAsyncTestSuite {
  this: AsyncTestSuite with BasicSpecBase =>

  registerChecker(new ConsoleChecker)
}


class ConsoleChecker extends Checker {

  type Issue = String
  type Context = Inspectors

  val ignoreTagNames: Set[String] = Set(IgnoreConsole).map(_.name)

  override def setup(): Inspectors = {
    val inspectors = Inspectors()
    System.setOut(inspectors.outps)
    System.setErr(inspectors.errps)
    inspectors
  }

  override def wrap[T](inspectors: Inspectors)(block: => T): T = {
    Console.withErr(inspectors.errps) {
      Console.withOut(inspectors.outps) {
        block
      }
    }
  }

  override def check(i: Inspectors): Unit = {

    val errLines = i.err.getLines
    if (errLines.nonEmpty) throw new FailedCheckException("Error output (STDERR) found", errLines)

    val suspLines = i.out.getLines map removeColorCodes filter suspicious
    if (suspLines.nonEmpty) throw new FailedCheckException("Suspicious console output found", suspLines)

  }

  override def teardown(i: Inspectors): Unit = {
    i.out.reset()
    i.err.reset()
    System.setOut(i.existingOut)
    System.setErr(i.existingErr)
  }

  def removeColorCodes(str: String): String = str.replaceAll("\u001B\\[[;\\d]*m", "")

  /**
    * This partial function allows to define output that is suspicious.
    * Note, to avoid marking log messages as suspicious, some are included.
    * WARN and ERROR log messages are not explicitly filtered out, so they
    * will show up as suspicious. This is overlapping with ChecksLogs, but
    * this is OK because ChecksLogs and ChecksConsole are independent and
    * there is no guarantee they both will be mixed in to any given test
    * suite. Besides, on any overlapping event, the first Checker registered
    * will throw a FailedCheckException and it doesn't really matter which
    * Checker throws it, as long as the test fails appropriately.
    *
    * @return true if the input is suspicious, false if it is not
    */
  def suspicious: String ?=> Boolean = {
    case r"##teamcity.*" => false //somehow these teamcity lines show up, and they appear innocuous
    case "" => false //blank lines are OK
    case r".*\[INFO \].*" => false //info log messages should be skipped
    case r"\[DEBUG\].*" => false //debug log messages should be skipped
    case r"\[TRACE\].*" => false //trace log messages should be skipped
    case _ => true //this should help catch any direct console output, which shouldn't be happening because we should be using logs
  }

}

object IgnoreConsole extends Tag("IgnoreConsole")

case class Inspectors( out: ConsoleOutInspector = new ConsoleOutInspector,
                       err: ConsoleErrInspector = new ConsoleErrInspector,
                       existingOut: PrintStream = System.out,
                       existingErr: PrintStream = System.err) {
  lazy val outps = new PrintStream(out)
  lazy val errps = new PrintStream(out)
}

class ConsoleOutInspector extends OutputStreamInspector(Console.out)

class ConsoleErrInspector extends OutputStreamInspector(Console.err)

class OutputStreamInspector(stream: OutputStream) extends TeeOutputStream(stream, new ByteArrayOutputStream) {
  def getLines: Vector[String] = Source.fromString(tee.toString).getLines().toVector
  def reset(): Unit = {
    flush()
    tee.reset()
  }
}
