import DevEnvironmentTasks.agentJars
import K8sConfigTemplateGen.genConfig
import com.typesafe.config.{ConfigFactory, ConfigObject, ConfigOrigin, ConfigValue, ConfigValueType, ConfigUtil => TypesafeUtil}
import sbt.Keys._
import sbt.internal.util.ManagedLogger
import sbt.{Compile, IO, taskKey, _}
import Util.searchForAdditionalJars
import sbtassembly.AssemblyKeys.assemblyOutputPath
import sbtassembly.AssemblyPlugin.autoImport.assembly

import java.io.File
import java.nio.file.Path
import scala.collection.JavaConverters._

object K8sTasks {
  def init(libindyVer: String) = {
    Seq(
      k8sDockerPackageDir := { target.value / "docker" },
      k8sConfigGenDir := { k8sDockerPackageDir.value / "configuration" },
      k8sConfigGen := {
        val log = streams.value.log

        val referenceConf = (Compile / resources)
          .value
          .find(_.toString.endsWith("reference.conf"))
          .map(_.toPath)
          .getOrElse(throw new Exception("reference.conf file was not found"))

        log.info(s"Generating docker container 'application.conf' from ${referenceConf.toString}")

        val generatedConfig = genConfig(referenceConf, log)
        val file = k8sConfigGenDir.value / "application.conf"
        log.info(s"Writing application.conf file to -- $file")
        IO.write(file, generatedConfig)
        file
      },
      k8sJars := {
        val log = streams.value.log
        log.info("Finding jars for Docker container")

        val assemblyFile = (assembly / assemblyOutputPath).value
        log.info(s"Found main assembly jar: $assemblyFile")

        val additionalJars = searchForAdditionalJars(
          (Compile / dependencyClasspath).value,
          agentJars.value
        )

        additionalJars.foreach(j => log.info(s"Found additional jar: ${j._1}"))

        Seq(assemblyFile) ++ additionalJars.map(_._1)
      },
      k8sDockerPackage := {
        val log = streams.value.log
        log.info("Collecting files for Docker container build")
        k8sConfigGen.value


        val jarDest = k8sDockerPackageDir.value / "jars"
        log.info(s"Writing jars to -- $jarDest")

        jarDest.mkdirs()
        val jarsToCopy = k8sJars.value
        jarsToCopy.foreach{ j =>
          val dest = jarDest / j.toPath
            .getFileName
            .toString
            .replaceFirst("-\\d.*.jar", ".jar")
          log.info(s"Copy jars -- $j to $dest")
          IO.copyFile(j, dest)
        }

        val scriptSource = sourceDirectory.value / "docker" / "scripts"
        val scriptDest = k8sDockerPackageDir.value / "scripts"

        log.info(s"Writing scripts to -- $scriptDest")
        IO.copyDirectory(scriptSource, scriptDest)

        val dockerFileSource = sourceDirectory.value / "docker" / "Dockerfile"
        val dockerFileDest = k8sDockerPackageDir.value / "Dockerfile"
        log.info(s"Writing Dockerfile to -- $dockerFileDest")
        IO.copyFile(dockerFileSource, dockerFileDest)

        val argFileDest = k8sDockerPackageDir.value / "args.env"
        val argsScript =
          s"""
            |export VERITY_VERSION="${version.value}"
            |export LIBINDY_VERSION="$libindyVer"
            |""".stripMargin

        log.info(s"Writing args.env to -- $argFileDest")
        IO.write(argFileDest, argsScript)
      }
    )
  }

  val k8sJars = taskKey[Seq[File]]("List of Jars to package")
  val k8sConfigGen = taskKey[File]("Generate application.conf file to be packaged in Docker container")
  val k8sConfigGenDir = taskKey[File]("Location for generated config for Docker container")
  val k8sDockerPackageDir = taskKey[File]("Location for Docker build")
  val k8sDockerPackage = taskKey[Unit]("Prepare files for Docker build")
}

object K8sConfigTemplateGen {
  def genConfig(referencePath: Path, logger: ManagedLogger) = {
    val config = ConfigFactory.parseFile(
      referencePath.toFile
    )

    implicit val loggerObj = logger

    implicit val explicitSkips: Seq[String] = config
      .getList("verity.config.k8s-skips")
      .asScala
      .map{x =>
        x.unwrapped().toString
      }

    val explicitIncludes: Seq[String] = config
      .getList("verity.config.k8s-includes")
      .asScala
      .map{x =>
        x.unwrapped().toString
      }

    val collection = (
      convertConfigObject(Seq.empty, config.resolve().root()) ++
        collectIncludes(explicitIncludes)
      ).sorted

    collection.mkString("", "\n", "\n\n") + """include "config-map/sponsors.conf"
                                  |include "config-map/ledgers.conf"
                                  |include "config-map/usage-rules.conf"
                                  |include "config-map/metrics.conf"
                                  |include "config-map/customer.conf"
                                  |include "config-map/custom.conf"""".stripMargin
  }

  def pathToEnvVar(path: String): String = path
    .replace("-", "_")
    .replace(".", "__")
    .toUpperCase()

  def pathToEnvVar(path: Seq[String]): String = pathToEnvVar(TypesafeUtil.joinPath(path.asJava))

  def buildLine(pathStr: String, envVarStr:String, defaultStr: Option[String]) = {
    val default = defaultStr
      .map(s => s"# Default: $s")
      .getOrElse("# Default value is NOT defined")
    s"$pathStr = $${?$envVarStr} $default"
  }

  def primitiveTypeLine(path: Seq[String], value: ConfigValue) = {
    buildLine(
      TypesafeUtil.joinPath(path.asJava),
      pathToEnvVar(path),
      Some(value.render())
    )
  }

  def filterByOrigin(origin: ConfigOrigin): Boolean = {
    val notFromReference = !Option(origin.url()).exists(_.toString.endsWith("verity/src/main/resources/reference.conf"))
    val skip = origin.comments().asScala.exists(
      _.toUpperCase()
        .trim
        .replaceAll("\\s", "")
        .replace("-", "_")
        .contains("<K8S_SKIP>")
    )

    skip || notFromReference
  }

  def filterByPath(currentPath: Seq[String])
                  (implicit explicitSkips: Seq[String]): Boolean = {
    if(currentPath.isEmpty) false
    else explicitSkips.contains(TypesafeUtil.joinPath(currentPath.asJava))
  }

  def shouldFilter(currentPath: Seq[String],
                   value: ConfigValue)
                  (implicit explicitSkips: Seq[String],
                   logger: ManagedLogger): Boolean = {
    val rtn = filterByOrigin(value.origin()) || filterByPath(currentPath)
    if(rtn) logger.debug(s"skip: ${TypesafeUtil.joinPath(currentPath.asJava)}")
    rtn
  }


  def collect(collection: Seq[String])
             (currentPath: Seq[String], value: ConfigValue)
             (implicit explicitSkips: Seq[String]): Seq[String] = {
    collection :+ primitiveTypeLine(currentPath, value)
  }

  def convertConfigObject(currentPath: Seq[String],
                          value: ConfigValue,
                          collection: Seq[String] = Seq.empty)
                         (implicit explicitSkips: Seq[String],
                          logger: ManagedLogger): Seq[String] = {
    if(shouldFilter(currentPath, value)) collection
    else {
      value.valueType() match {
        case ConfigValueType.OBJECT if value.asInstanceOf[ConfigObject].isEmpty =>
          logger.info(s"[EMPTY-OBJECT] ${TypesafeUtil.joinPath(currentPath.asJava)}")
          collection
        case ConfigValueType.OBJECT =>
          value.asInstanceOf[ConfigObject]
            .asScala
            .flatMap { x =>
              convertConfigObject(
                currentPath :+ x._1,
                x._2
              )
            }
            .toSeq
        case ConfigValueType.STRING => collect(collection)(currentPath, value)
        case ConfigValueType.BOOLEAN => collect(collection)(currentPath, value)
        case ConfigValueType.NUMBER => collect(collection)(currentPath, value)
        case ConfigValueType.NULL => collect(collection)(currentPath, value)
        case ConfigValueType.LIST =>
          logger.info(s"[LIST] ${TypesafeUtil.joinPath(currentPath.asJava)}")
          collection
        case _ => collection
      }
    }

  }

  def collectIncludes(include: Seq[String]): Seq[String] = {
    include.map{ i =>
      buildLine(
        i,
        pathToEnvVar(i),
        None
      )
    }
  }

}

