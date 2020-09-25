package com.evernym.integrationtests.e2e.sdk.process

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.process.ProcessSdkProvider._
import com.evernym.integrationtests.e2e.sdk.{BaseSdkProvider, ListeningSdkProvider}
import com.evernym.verity.sdk.protocols.relationship.v1_0.GoalCode
import com.evernym.verity.sdk.utils.{AsJsonObject, Context}
import org.json.JSONObject

import scala.language.{implicitConversions, postfixOps}
import scala.sys.process._

trait ProcessSdkProvider
  extends BaseSdkProvider
    with ListeningSdkProvider {

  private def printOut(output: String, outType: String = "OUTPUT"): Unit = {
    println(s"====================    ${outType.capitalize}    ====================")
    println(output)
    println("====================  END OUTPUT  ====================")
  }
  private def printScript(script: String): Unit = {
    println("==================== SCRIPT START ====================")
    println(script)
    println("====================  SCRIPT END  ====================")
  }

  case class RunSdkBuilder(script: String, provider: ProcessSdkProvider) {
    def execute(): String = {
      if(print)printScript(script)

      val env = provider.interpreter
      val scriptStream = new ByteArrayInputStream(script.getBytes())

      def executeScript(tries: Int, lastException: Option[Throwable]=None): String = {
        if(tries >= 2) throw lastException.get

        val outBuffer = new StringBuffer
        val errBuffer = new StringBuffer

        val log: ProcessLogger = new ProcessLogger {
          override def out(s: => String): Unit = outBuffer.append(s)
          override def err(s: => String): Unit = errBuffer.append(s)
          override def buffer[T](f: => T): T = f
        }

        val code = Process(
          env.cmd +: env.args,
          env.cwd.toFile,
          env.envVars.toArray: _*
        )
        .#<{
          scriptStream
        }.!(log)

        def printProcessOutput(): Unit = {
          val out = outBuffer.toString
          val err = errBuffer.toString

          if(print && out.nonEmpty) printOut(out)
          if(print && err.nonEmpty) printOut(err, "ERROR")

        }

        if(code == 134) {
          printOut(outBuffer.toString)
          printOut(errBuffer.toString, "ERROR")
          executeScript(tries+1, Some(new RuntimeException("Nonzero exit value: " + code)))
        }
        else if (code != 0) {
          printProcessOutput()
          sys.error("Nonzero exit value: " + code)
        }
        else {
          val out = outBuffer.toString
          printProcessOutput()
          out
        }
      }

      executeScript(0)
    }

  }

  implicit def stringToProcess(script: String): RunSdkBuilder = apply(script)

  def mkParams(strings: Seq[Any]): String = {
    strings
      .map {
        case g: GoalCode => convertGoalCode(g)
        case b: Boolean => booleanParam(b)
        case s: String => stringParam(s)
        case Some(s: String) => stringParam(s)
        case s: Seq[_] => {s"[ ${mkParams(s)} ]"}
        case None => noneParam
        case d: AsJsonObject => jsonParam(d)
        case m: Map[_, _] => jsonParam(MapAsJsonObject(m))
      }
      .mkString(", ")
  }

  def convertGoalCode(goal: GoalCode): String

  def executeOneLine(ctx: Context, imports: String, cmd: String): String = {
    val contextStr = ctx.toJson.toString()

    val script = buildScript(
      imports,
      contextStr,
      cmd
    )

    script.execute().trim
  }

  def booleanParam: Boolean => String
  def stringParam: String => String = {s => "\"" + s.replaceAllLiterally("\"", "\\\"") + "\""}
  def noneParam: String
  def jsonParam: AsJsonObject => String

  def testDir: Path

  def sdkTypeAbbreviation: String

  lazy val interpreterWorkingDir: Path =
    Files.createTempDirectory(testDir, sdkTypeAbbreviation)

  def packageUrl(repo: String, version: String, fileType: String): String = {
    s"https://repo.corp.evernym.com/filely/$repo/verity-sdk_$version.$fileType"
  }

  def interpreter: InterpreterEnv

  def sdkConfig: SdkConfig
  def sdkVersion: String =
    sdkConfig.version.getOrElse(throw new Exception("This sdk requires a version to be configured"))

  def print: Boolean = true

  def buildScript(imports: String, context: String, cmd: String): String

  def apply(script: String) = {
    RunSdkBuilder(script, this)
  }
}

object ProcessSdkProvider {
  case class InterpreterEnv(cmd: String,
                            cwd: Path,
                            args: Seq[String] = Seq.empty,
                            envVars: Map[String, String] = Map.empty)

  case class MapAsJsonObject(map: Map[_, _]) extends AsJsonObject {
    override def toJson: JSONObject = {
      map.foldLeft(new JSONObject()){
        (o, e) =>
          o.put(e._1.toString, e._2.toString)
      }
    }
  }

  def versionToModule(version: String) = {
    "v" + version.replace('.', '_')
  }
}