package com.evernym.integrationtests.e2e.sdk.process

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.process.ProcessSdkProvider._
import com.evernym.integrationtests.e2e.sdk.{BaseSdkProvider, ListeningSdkProvider}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.sdk.protocols.relationship.v1_0.GoalCode
import com.evernym.verity.sdk.utils.{AsJsonObject, Context}
import com.typesafe.scalalogging.Logger
import org.json.JSONObject

import java.io.{BufferedWriter, ByteArrayInputStream, File, FileWriter}
import java.nio.file.{Files, Path}
import scala.language.{implicitConversions, postfixOps}
import scala.sys.process._

trait ProcessSdkProvider
  extends BaseSdkProvider
    with ListeningSdkProvider {

  val logger: Logger = getLoggerByName(getClass.getName)

  private def printOut(output: String, outType: String = "OUTPUT"): Unit = {
    logger.debug(
      s"""====================    ${outType.capitalize}    ====================
         |$output
         |====================  END OUTPUT  ====================""".stripMargin
    )
  }
  private def printScript(script: String): Unit = {
    logger.debug(
      s"""==================== SCRIPT START ====================
         |$script
         |====================  SCRIPT END  ====================""".stripMargin
    )
  }

  case class RunSdkBuilder(script: String, provider: ProcessSdkProvider) {
    def execute(): String = {
      printScript(script)

      val env = provider.interpreter


      def executeScript(tries: Int, lastException: Option[Throwable]=None): String = {
        if(tries >= 2) throw lastException.get

        val outBuffer = new StringBuffer
        val errBuffer = new StringBuffer

        val log: ProcessLogger = new ProcessLogger {
          override def out(s: => String): Unit = outBuffer.append(s)
          override def err(s: => String): Unit = errBuffer.append(s)
          override def buffer[T](f: => T): T = f
        }

        val totalCmd = env.toProcess match {
          case ProcessSdkProvider.File =>
            val file = File.createTempFile(sdkTypeAbbreviation, fileSuffix, interpreterWorkingDir.toFile)
            val bw = new BufferedWriter(new FileWriter(file))
            bw.write(script)
            bw.close()

            Some(Seq(env.cmd) ++ env.args ++ Seq(file.toPath.toAbsolutePath.toString))
          case ProcessSdkProvider.Stream =>
            Some(Seq(env.cmd) ++ env.args)
          case _ => None
        }

        val scriptStream = env.toProcess match {
          case ProcessSdkProvider.File =>
            None
          case ProcessSdkProvider.Stream =>
            Some(new ByteArrayInputStream(script.getBytes()))
          case _ => None
        }

        val code = totalCmd.map { cmd =>
          val p = Process(
            cmd,
            env.cwd.toFile,
            env.envVars.toArray: _*
          )
          scriptStream match {
            case Some(s) => p.#<(s)
            case None => p
          }
        }
        .map(
          _.!(log)
        )
        .getOrElse(throw new Exception("Unable to build process for sdk provider"))



        def printProcessOutput(): Unit = {
          val out = outBuffer.toString
          val err = errBuffer.toString

          if(out.nonEmpty) printOut(out)
          if(err.nonEmpty) printOut(err, "ERROR")

        }

        if(code == 134) {
          printOut(outBuffer.toString)
          printOut(errBuffer.toString, "ERROR")
          executeScript(tries+1, Some(new RuntimeException("Nonzero exit value: " + code)))
        }
        else if (code == sdkErrExitCode) {
          val err = errBuffer.toString
          throw SdkProviderException(err)
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
        case m: Map[_, _] => mapParam(m)
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
  def mapParam: Map[_, _] => String
  def noneParam: String
  def jsonParam: AsJsonObject => String
  def rawJsonParam: AsJsonObject => String

  def testDir: Path

  def sdkTypeAbbreviation: String
  def fileSuffix: String

  lazy val interpreterWorkingDir: Path =
    Files.createTempDirectory(testDir, sdkTypeAbbreviation)

  def packageUrl(repo: String, version: String, fileType: String): String = {
    s"https://repo.corp.evernym.com/filely/$repo/verity-sdk_$version.$fileType"
  }

  def interpreter: InterpreterEnv

  def sdkConfig: SdkConfig
  def sdkVersion: String =
    sdkConfig.version.getOrElse(throw new Exception("This sdk requires a version to be configured"))

  def buildScript(imports: String, context: String, cmd: String): String

  def apply(script: String) = {
    RunSdkBuilder(script, this)
  }
}

object ProcessSdkProvider {

  val sdkErrExitCode = 100

  sealed trait ToProcessStrategy

  case object File extends ToProcessStrategy
  case object Stream extends ToProcessStrategy
  
  case class InterpreterEnv(cmd: String,
                            cwd: Path,
                            args: Seq[String] = Seq.empty,
                            envVars: Map[String, String] = Map.empty,
                            toProcess: ToProcessStrategy = Stream)

  case class MapAsJsonObject(map: Map[_, _]) extends AsJsonObject {
    override def toJson: JSONObject = {
      map.foldLeft(new JSONObject()){
        (o, e) =>
          o.put(e._1.toString, e._2.toString)
      }
    }
  }

  case class TokenAsJsonObject(token: String) extends AsJsonObject {
    override def toJson: JSONObject = new JSONObject(token)
  }

  def versionToModule(version: String) = {
    "v" + version.replace('.', '_')
  }

  def writeConfigFile(cwd: Path, fileName: String, contents: String) = {
    val file = new File(cwd.resolve(fileName).toString)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(contents)
    bw.close()
  }
}


case class SdkProviderException(errorMessage: String) extends RuntimeException {
  override def getMessage: String = errorMessage
}