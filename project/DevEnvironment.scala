import SharedLibraryTasks.sharedLibraries
import sbt.Keys.{sbtVersion, update}
import sbt.{ThisBuild, taskKey}

import java.io.IOException
import scala.io.AnsiColor._
import scala.language.postfixOps
import scala.math.max
import scala.sys.process._
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object Need {
  sealed trait NeedScope

  case object Core extends NeedScope
  case object Integration extends NeedScope
  case object IntegrationSDK extends NeedScope
}

object DevEnvironmentTasks {
  //noinspection TypeAnnotation
  def init = {
    Seq(
      ThisBuild / envFullCheck := {
        val jdkVer = jdkExpectedVersion.value
        val sbtVer = sbtVersion.value
        val libs = sharedLibraries.value
        val repos = envRepos.value
        update.value
//        updateSharedLibraries.value
        DevEnvironment.fullCheck(repos, libs, jdkVer, sbtVer)
      },
      ThisBuild / envOsCheck := {
        DevEnvironment.checkOS()
      },
      ThisBuild / envNativeLibCheck := {
        val libs = sharedLibraries.value
        DevEnvironment.checkNativeLibs(DevEnvironment.checkOS(), libs)
      }
    )
  }
  val envFullCheck = taskKey[Boolean]("Check for required/optional environment elements")
  val envOsCheck= taskKey[Option[Ubuntu]]("Setup environment elements")
  val envNativeLibCheck = taskKey[Boolean]("Check for native libraries")
  val envRepos = taskKey[Seq[DevEnvironment.DebianRepo]]("Repos need for the native libs")
  val jdkExpectedVersion = taskKey[String]("Expected version of JDK (not enforced)")
  val agentJars = taskKey[Seq[String]]("Agent jars")
}

//noinspection NameBooleanParameters
object DevEnvironment {
  import DevEnvironmentUtil._

  case class DebianRepo(repo: String, release: String, components: String, keyFingerPrint: String, keyLoc: String) {
    override def toString: String = s"$repo $release $components"
  }

  def fullCheck(repos: Seq[DebianRepo], libs: Seq[SharedLibrary.Lib], jdkVer: String, sbtVer: String): Boolean = {
    val os = checkOS()
    Seq(
      os.isDefined,
      checkExtraTools(os),
      checkCertificate(),
      checkScala(jdkVer, sbtVer),
      checkAptRepos(os, repos),
      checkNativeLibs(os, libs),
      checkDocker(),
      checkDevlab(),
      checkPythonSdk(),
      checkNodeSdk(),
      checkDotNetSdk()
    ).forall(identity)
  }

  def checkOS(indent: Int = 2): Option[Ubuntu] = {
    printCheckHeading("OS", Need.Core)
    val os = sys.props.get("os.name")

    val osType = os match {
      case Some("Linux") =>
        printDetection("OS", "Linux", true, indent)
        run("cat /etc/os-release")
          .map { content =>
            if(content.contains("ID=ubuntu") || content.contains("ID_LIKE=ubuntu")) {
              content
                .linesIterator
                .find(_.startsWith("UBUNTU_CODENAME="))
                .map{ x =>
                  x.splitAt(x.indexOf("=")+1)._2
                }
                .map(Ubuntu(_, false))
            }
            else {
              None
            }
          }
          .getOrElse(None)
      case _ =>
        print((" " * indent) + YELLOW_B + "Automatic setup actions require Ubuntu" + RESET)
        print((" " * indent) + YELLOW_B + "Other Detections will still be attempt" + RESET)
        None
    }

    printDetection("Distribution", osType, osType.isDefined, indent)

    val toolsCheck = osType match {
      case Some(_: Ubuntu) =>
        val dpkgVer = run("dpkg --version")
        val aptVer = run("apt-get -v")
        Seq(
          printDetection("dpkg", dpkgVer, hasVer(dpkgVer), indent),
          printDetection("apt", aptVer, hasVer(aptVer), indent)
        ).forall(identity)
      case _ => true
    }

    osType.map(_.copy(toolsCheck=toolsCheck))
  }

  def checkCertificate(indent: Int = 2): Boolean = {
    printCheckHeading("Certificate", Need.Core)
    val canCurlGitlabReqCert = run("curl -s -o /dev/null https://gitlab.corp.evernym.com/")
    val canCurlGitlabWithoutCert = canCurlGitlabReqCert.transform(
      _ => Success(""), // No need to rerun if successful with cert
      _ => run("curl -k -s -o /dev/null https://gitlab.corp.evernym.com/")
    )

    val rtn = if(canCurlGitlabReqCert.isSuccess) {
      printDetection("Certificate", "", true, indent)
    }
    else if (canCurlGitlabReqCert.isFailure && canCurlGitlabWithoutCert.isFailure) {
      printDetection("Certificate", "Unable to reach evernym gitlab (vpn maybe?)", false, indent)
    }
    else if (canCurlGitlabReqCert.isFailure && canCurlGitlabWithoutCert.isSuccess) {
      printDetection("Certificate", "Missing Evernym certificate", false, indent)
    }
    else {
      printDetection("Certificate", "Unable check certificate", false, indent)
    }

    if (!rtn) println(s"""${" "*(indent+2)} [INSTALL CERT] -- see: https://docs.google.com/document/d/1XnKbaF1CYLa2MXcwZv1KexXokGfU4EaT-yyNpki_5j0""")

    rtn
  }

  def checkScala(jdkVer: String, sbtVer: String, indent: Int = 2): Boolean = {
    printCheckHeading("Scala", Need.Core)
    val checkJdkVer = run("javac -version")
    printDetection("JDK", checkJdkVer, eqVer(checkJdkVer, jdkVer), indent)

    printDetection("SBT", sbtVer, hasVer(Try(sbtVer)), indent)
  }

  def checkExtraTools(os: Option[Ubuntu], indent: Int = 2): Boolean = {
    printCheckHeading("Extra Tools", Need.Core)
    os match {
      case Some(u: Ubuntu) =>
        Seq(
          u.checkPkg("libatomic1", None, Some("protobuf"), indent),
          u.checkPkg("fakeroot", None, Some("debian packaging"), indent),
          u.checkPkg("curl", None, Some("cert check"), indent)
        ).forall(identity)
      case _ =>
        println("Unable to check Native Libs for a Non-Ubuntu OS")
        false
    }
  }

  def checkAptRepos(os: Option[Ubuntu], repos: Seq[DebianRepo], indent: Int = 2): Boolean = {
    printCheckHeading("Debian Repos", Need.Core)
    os match {
      case Some(u: Ubuntu) =>
        repos
          .map(u.checkRepo(_, indent))
          .forall(identity)
      case _ =>
        println("Unable to check Native Libs for a Non-Ubuntu OS")
        false
    }
  }

  //noinspection ScalaUnusedSymbol
  def checkNativeLibs(os: Option[Ubuntu], libs: Seq[SharedLibrary.Lib], indent: Int = 2): Boolean = {
    printCheckHeading("Native Libraries", Need.Core)
    os match {
      case Some(u: Ubuntu) =>
        libs
          .map(u.checkNativeLibPkg(_, indent))
          .forall(identity)
      case _ =>
        println("Unable to check Native Libs for a Non-Ubuntu OS")
        false
    }

  }

  def checkDocker(indent: Int = 2): Boolean = {
    printCheckHeading("Docker", Need.Integration)
    val ver = run("docker --version")
    printDetection("Docker", ver, hasVer(ver), indent)
  }

  def checkDevlab(indent: Int = 2): Boolean = {
    printCheckHeading("Devlab", Need.Integration)
    val ver = run("devlab -v")
    printDetection("Devlab", ver, hasVer(ver), indent)
  }

  def checkPythonSdk(indent: Int = 2): Boolean = {
    printCheckHeading("Python SDK", Need.IntegrationSDK)
    val ver = run("virtualenv --version")
    printDetection("python virtualenv", ver, hasVer(ver), indent)
  }

  def checkNodeSdk(indent: Int = 2): Boolean = {
    printCheckHeading("Node SDK", Need.IntegrationSDK)
    val npmVer = run("npm -v")
    val nodeVer = run("node --version")
    Seq(
      printDetection("npm", npmVer, hasVer(npmVer), indent),
      printDetection("node", nodeVer, hasVer(nodeVer), indent)
    ).forall(identity)
  }

  def checkDotNetSdk(indent: Int = 2): Boolean = {
    printCheckHeading("Dot Net SDK", Need.IntegrationSDK)
    println(s"${" "*indent}${RED}Unable to detect Dot Net Tools$RESET")
    true
  }
}

sealed trait OS {
  def checkRepo(repo: DevEnvironment.DebianRepo, indent: Int = 2): Boolean
  def checkPkg(packageName: String, packageVersion: Option[String], reason: Option[String], indent: Int = 2): Boolean
  def checkNativeLibPkg(lib: SharedLibrary.Lib, indent: Int = 2): Boolean
}

case class Ubuntu(codeName: String, toolsCheck: Boolean) extends OS {
  import DevEnvironmentUtil._
  import DevEnvironment.DebianRepo
  val version: String = codeName match {
    case "xenial" => "16.04"
    case "bionic" => "18.04"
    case "focal" => "20.04"
  }

  override def checkRepo(repo: DebianRepo, indent: Int = 2): Boolean = {
    val fingers = run("apt-key finger")
    val hasRepoKey = printDetection(
      "Repo Signing Key",
      repo.keyFingerPrint,
      fingers.map(_.contains(repo.keyFingerPrint)).getOrElse(false),
      indent
    )

    if (!hasRepoKey) println(s"""${" "*(indent+2)} [INSTALL KEY] -- curl ${repo.keyLoc} | apt-key add -""")

    val repos = run("grep -rhE ^deb /etc/apt/")
      .map(_.linesIterator.toList)
      .map(_.map(_.trim.substring(4)))

    val hasEvernymRep = repos.toOption.exists(_.contains(repo.toString))
    val hasRepo = printDetection(
      s"Debian Repos - $repo",
      if(hasEvernymRep) "found" else "missing",
      hasEvernymRep,
      indent
    )
    if (!hasRepo) println(s"""${" "*(indent+2)} [INSTALL REPO] -- sudo add-apt-repository "deb $repo"""")

    hasRepo && hasRepoKey
  }

  override def checkPkg(packageName: String, packageVersion: Option[String], reason: Option[String], indent: Int = 2): Boolean = {
    val lines = run(s"dpkg -s $packageName")
      .map(_.linesIterator.toSeq)

    val statusInstalled = lines
      .map(_.exists(_.matches("Status:.*installed")))

    val installedVersion = lines
      .map(_.find(_.startsWith("Version:")))
      .flatMap { o =>
        Try(o.getOrElse(throw new Exception("Unable to find Version of package")))
      }
      .map { version =>
        packageVersion match {
          case Some(v: String) => (version, version.contains(v))
          case None => (version, true)
        }
      }

    val isValidInstall = for(
      status <- statusInstalled;
      version <- installedVersion
    ) yield status && version._2

    val reasonStr = reason.map(r=>s" (for $r)").getOrElse("")
    val rtn = printDetection(
      s"$packageName$reasonStr",
      installedVersion.map(_._1).getOrElse(""),
      isValidInstall.getOrElse(false),
      indent
    )
    if(!rtn) {
      val pkgStr = s"$packageName${packageVersion.map("="+_).getOrElse("")}"
      println(s"${" "*(indent+2)} [INSTALL PACKAGE] -- sudo apt install $pkgStr")
    }
    rtn
  }

  override def checkNativeLibPkg(lib: SharedLibrary.Lib, indent: Int = 2): Boolean = {
    val packageName = lib.packageName
    val expectedVer = lib.packageVersion(this)

    checkPkg(packageName, Some(expectedVer), None, indent)
  }
}

object DevEnvironmentUtil {
  def printDetection(lookingFor: Any, found: Any, isOk: => Boolean, indent: Int): Boolean = {
    val MAX_LINE_SIZE = 130

    val isOkBool = isOk
    val indentStr = " " * indent
    val foundStr = {
      val s = found match {
        case Success(s: String) => s
        case Failure(t: Throwable) => t.getMessage
        case Some(s) => s.toString
        case s => s.toString
      }
      s.linesIterator.mkString(" -- ").replaceAll("\\s", " ")
    }

    val detectionStr = s"${MAGENTA}Detected $lookingFor:$RESET "

    val status = if (isOkBool) {
      s"$GREEN[OK]$RESET"
    } else {
      s"$RED[BAD]$RESET"
    }

    val str = {
      val requiredLength = indentStr.length + detectionStr.length + status.length
      val finalFoundStr = if(requiredLength + foundStr.length > MAX_LINE_SIZE) {
        foundStr.substring(0, MAX_LINE_SIZE-requiredLength)
      } else {
        foundStr
      }

      val mainStr = s"$indentStr$detectionStr$finalFoundStr"
      val pad = max(MAX_LINE_SIZE - (mainStr.length+status.length-2), 2)
      s"$mainStr ${"-"*pad} $status"
    }
    println(str)
    isOkBool
  }

  def printCheckHeading(checking: String, need: Need.NeedScope): Unit = {
    val needStr = need match {
      case Need.Core =>  s"$BLUE[${Need.Core}]$RESET"
      case Need.Integration =>  s"$YELLOW[${Need.Integration}]$RESET"
      case Need.IntegrationSDK =>  s"$YELLOW[${Need.IntegrationSDK}]$RESET"
    }

    val str = s"Checking $checking: $needStr"
    println(str)
  }

  def run(cmd: String): Try[String] = {
    Try {
      val outBuffer = new StringBuffer

      val log: ProcessLogger = new ProcessLogger {
        override def out(s: => String): Unit = {
          outBuffer.append(s)
          outBuffer.append("\n")
        }
        override def err(s: => String): Unit = out(s)
        override def buffer[T](f: => T): T = f
      }

      val returnCode = cmd ! log
      if(returnCode == 0) {
        outBuffer.toString
      }
      else {
        throw new Exception(s"cmd failed with $returnCode -- ${outBuffer.toString}")
      }

    }
      .transform(Success(_), {
        case e: IOException if e.getMessage.contains("No such file or directory") =>
          Failure(new Exception("command not found"))
        case e =>
          Failure(e)
      })
  }

  def eqVer(text: Try[String], ver: String): Boolean = {
    text
      .map(findVer)
      .map(_.contains(ver))
      .getOrElse(false)
  }

  def hasVer(text: Try[String]): Boolean = {
    text
      .map(findVer)
      .map(_.isDefined)
      .getOrElse(false)
  }

  val FLOATING_POINT_VERSION_PAT: Regex = "(\\d+\\.\\d+\\.\\d+)".r

  def findVer(text:String): Option[String] = {
    FLOATING_POINT_VERSION_PAT.findFirstIn(text)
  }
}
