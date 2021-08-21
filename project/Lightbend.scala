import sbt._

import java.io.File
import scala.reflect.io.Path
import scala.util.Try

object Lightbend {
  //noinspection TypeAnnotation
  def init() = {
    Seq(
      lightbendHasCred := lightbendCred.value.isDefined,
      lightbendCredFilePath := Path.apply(System.getenv("HOME")) / ".sbt" / "lightbend_cred.txt",
      lightbendCred := {
        val credTokenFile = sys.env.get("LIGHTBEND_CRED_TOKEN_FILE")
          .map(new File(_))
          .orElse(Option(lightbendCredFilePath.value.jfile))
        val credToken = credTokenFile.flatMap(f => Try(IO.read(f)).toOption.map(_.trim))

        credToken match {
          case Some(_) => println(s"Lightbend commercial token found in '${credTokenFile.getOrElse("")}'")
          case None => println(s"No lightbend commercial token detected (expected to find it in '${credTokenFile.getOrElse("")}'")
        }

        credToken
      },
      lightbendResolvers := {
        if(lightbendHasCred.value) {
          Seq(
            "lightbend-commercial-mvn" at s"https://repo.lightbend.com/pass/${lightbendCred.value.get}/commercial-releases",
            Resolver.url(
              "lightbend-commercial-ivy",
              url(s"https://repo.lightbend.com/pass/${lightbendCred.value.get}/commercial-releases")
            )(Resolver.ivyStylePatterns)
          )
        } else Seq.empty
      },
      lightbendDeps := {
        if(lightbendHasCred.value) {
          Seq(
            "com.lightbend.cinnamon" % "cinnamon-agent" % lightbendCinnamonVer.value % "provided",
            "com.lightbend.cinnamon" %% "cinnamon-akka" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" %% "cinnamon-akka-typed" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" %% "cinnamon-akka-persistence" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" %% "cinnamon-akka-http" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" % "cinnamon-datadog" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" % "cinnamon-chmetrics" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" %% "cinnamon-opentracing" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" % "cinnamon-opentracing-datadog" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" %% "cinnamon-slf4j-mdc" % lightbendCinnamonVer.value,
            "com.lightbend.cinnamon" % "cinnamon-jvm-metrics-producer" % lightbendCinnamonVer.value,

          )
        } else Seq.empty
      },
      lightbendCinnamonAgentJar := {
        if(lightbendHasCred.value) {
          Seq(
            "cinnamon-agent"
          )
        } else Seq.empty
      },
      lightbendClassFilter := {
        if(!lightbendHasCred.value) Some("*Lightbend*") else None
      }
    )
  }

  val lightbendHasCred = settingKey[Boolean]("True if lightbend commercial credential is available")
  val lightbendCredFilePath = settingKey[Path]("Lightbend commercial credential")
  val lightbendCred = settingKey[Option[String]]("Lightbend commercial credential")
  val lightbendResolvers = settingKey[Seq[Resolver]]("Resolvers for lightbend commercial")
  val lightbendDeps = settingKey[Seq[ModuleID]]("Dependencies for lightbend commercial")
  val lightbendCinnamonAgentJar = settingKey[Seq[String]]("Agent jar for lightbend commercial")
  val lightbendClassFilter = settingKey[Option[FileFilter]]("Class filters for lightbend objects in the code")

  val lightbendCinnamonVer = settingKey[String]("Version of Cinnamon")
}
