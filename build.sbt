/*
Scala's incremental compilation depends on file timestamps with millisecond resolution. Compressed artifacts passed
between jobs in a job's pipeline drop millisecond resolution on file stimestamps.

Use truncateStamps to allow compressed artifacts in the CI/CD pipeline passed from one stage to another in the
      CI/CD pipeline to remain valid (no need to recompile).

import Util.amGitlabCI

See https://docs.gitlab.com/ce/ci/caching/ for details and/or possible alternatives.
*/
import DevEnvironment.DebianRepo
import DevEnvironmentTasks.{agentJars, envRepos, jdkExpectedVersion}
import Lightbend.{lightbendCinnamonAgentJar, lightbendCinnamonVer, lightbendClassFilter, lightbendDeps, lightbendResolvers}
import SharedLibrary.{NonMatchingDistLib, NonMatchingLib}
import SharedLibraryTasks.{sharedLibraries, updateSharedLibraries}
import Util._
import Version._
import sbt.Keys.{libraryDependencies, organization, update}
import sbtassembly.AssemblyKeys.assemblyMergeStrategy
import sbtassembly.MergeStrategy

import scala.language.postfixOps


enablePlugins(JavaAppPackaging)

ThisBuild / jdkExpectedVersion := "11.0.13"

val evernymUbuntuRepo = DebianRepo(
  "https://repo.corp.evernym.com/deb",
  "evernym-ubuntu",
  "main",
  "954B 4A2B 453F 834B 6962  7B5F 5A45 7C93 E812 1A0A",
  "https://repo.corp.evernym.com/repo.corp.evenym.com-sig.key"
)

val evernymDevRepo = DebianRepo(
  "https://repo.corp.evernym.com/deb",
  "evernym-agency-dev-ubuntu",
  "main",
  "954B 4A2B 453F 834B 6962  7B5F 5A45 7C93 E812 1A0A",
  "https://repo.corp.evernym.com/repo.corp.evenym.com-sig.key"
)

//shared libraries versions
val libVdrToolsVer = "0.8.6~1711"
val sharedLibDeps = Seq(
  NonMatchingDistLib("libvdrtools", libVdrToolsVer, "libvdrtools.so"),
  NonMatchingLib("libvcx", "0.14.1-bionic~844", "libvcx.so")  // For integration testing ONLY
)

//deb package dependencies versions
val debPkgDepLibVdrToolsMinVersion = libVdrToolsVer

//dependency versions
val vdrtoolsWrapperVer  = "0.8.6-1711"
val akkaVer             = "2.6.19"
val akkaHttpVer         = "10.2.9"
val akkaMgtVer          = "1.1.3"
val alpAkkaS3Ver        = "3.0.3"
val alpAkkaKafkaVer     = "3.0.0"
val dynamoDbPersistence = "1.2.0-RC2"
val kamonVer            = "2.5.5"
val kanelaAgentVer      = "1.0.14"
val cinnamonVer         = "2.16.5"      //DON'T upgrade until VE-3553 is fixed
val jacksonVer          = "2.13.3"
val sdnotifyVer         = "1.3"
val cloudEventsVersion  = "2.3.0"

//test dependency versions
val scalatestVer        = "3.2.12"
val mockitoVer          = "1.17.7"
val veritySdkVer        = "0.6.1-b2158887"
val vcxWrapperVer       = "0.14.1.844"

val flexmarkVer         = "0.64.0"

// compiler plugin versions
val silencerVersion     = "1.7.9"

// a 'compileonly' configuration (see https://stackoverflow.com/questions/21515325/add-a-compile-time-only-dependency-in-sbt#answer-21516954)
val COMPILE_TIME_ONLY = "compileonly"
val CompileOnly = config(COMPILE_TIME_ONLY)

val majorNum = "2"
val minorNum = "19"
val patchNum = "0"

// I'm not sure why setting this keys don't resolve in all
// other scopes but it does not so we re-resolve it commonSettings
ThisBuild / major := majorNum
ThisBuild / minor := minorNum
ThisBuild / patch := patchNum
ThisBuild / build := buildNum(
  git.gitHeadCommitDate.value,
  git.gitHeadCommit.value,
  git.gitUncommittedChanges.value
)
ThisBuild / version := s"${major.value}.${minor.value}.${patch.value}.${build.value}"
maintainer := "Evernym Inc <dev@evernym.com>"

ThisBuild / javaOptions += s"-Djna.tmpdir=${target.value.getAbsolutePath}"
ThisBuild / javaOptions += s"-Djava.io.tmpdir=${target.value.getAbsolutePath}"
ThisBuild / sharedLibraries := sharedLibDeps
ThisBuild / envRepos := Seq(evernymDevRepo, evernymUbuntuRepo)

SharedLibraryTasks.init
DevEnvironmentTasks.init

lazy val root = (project in file("."))
  .aggregate(verity)

lazy val verity = (project in file("verity"))
  .enablePlugins(DebianPlugin)
  .configs(IntegrationTest)
  .settings(
    name := s"verity",
    settings,
    testSettings,
    packageSettings,
    protoBufSettings,
    coverageSettings,
    lightbendCommercialSettings,
    libraryDependencies ++= commonLibraryDependencies,
    // Conditionally download an unpack shared libraries
    update := update.dependsOn(updateSharedLibraries).value,
    K8sTasks.init(debPkgDepLibVdrToolsMinVersion)
  )

lazy val integrationTests = (project in file("integration-tests"))
  .settings(
    name := "integration-tests",
    settings,
    testSettings,
  ).dependsOn(verity % "test->test; compile->compile")

lazy val settings = Seq(
  organization := "com.evernym",
  scalaVersion := "2.13.8",

  agentJars := Seq("kanela-agent"),

  scalacOptions := Seq(
    "-feature",
    "-unchecked",
    "-deprecation",
    "-encoding",
    "utf8",
    //Option -Xmax-classfile-name was removed from scalac: https://github.com/scala/scala/pull/7497
    "-Xfatal-warnings",
    "-P:silencer:pathFilters=.*/tictactoe/Role.scala;.*/deaddrop/Role.scala"
  ),
  // ComilerPlugin to allow suppression of a few warnings so we can get a clean build
  libraryDependencies ++= Seq(
    compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
    "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
  ),
  resolvers += Resolver.mavenLocal,
//  resolvers += "Lib-indy" at "https://repo.sovrin.org/repository/maven-public", // this shouldn't be necessary since we're publishing vdr-tools to maven central
  resolvers += "libvcx" at "https://evernym.mycloudrepo.io/public/repositories/libvcx-java",
  resolvers += "evernym-dev-verity-sdk" at "https://gitlab.com/api/v4/projects/26760306/packages/maven",  // used to fetch java-sdk wrapper from main builds
  resolvers += "evernym-dev-vdr-tools" at "https://gitlab.com/api/v4/projects/27807222/packages/maven", // used to fetch java wrapper from main builds

  Test / parallelExecution := false,
  Test / logBuffered := false,
  Global / parallelExecution := false,
  assembly / assemblyMergeStrategy := mergeStrategy,
  assembly / test := Unit, // in all know contexts, assembly don't need to run tests
  // dependencyOverrides are added to effectively ignore (mute) the eviction warnings for direct dependencies due to
  // seemingly binary incompatible transitive dependencies. Both kamon and akka try hard to ensure binary compatibility
  // between minor releases. Defining dependencyOverrides will mute eviction warnings in `sbt evicted` output, but
  // does not mute the terse version of the warning(s) displayed in `sbt compile` output:
  // "[warn] There may be incompatibilities among your library dependencies; run 'evicted' to see detailed eviction
  // warnings."
  ivyConfigurations += CompileOnly.hide,
  // appending everything from 'compileonly' to unmanagedClasspath
  Compile / unmanagedClasspath ++= update.value.select(configurationFilter(COMPILE_TIME_ONLY)),
  ThisBuild / scapegoatVersion := "1.3.11",
) ++ Defaults.itSettings

lazy val testSettings = Seq (
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-h", (target.value / "test-reports" / name.value).toString),
  //Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-o"),             // standard test output, a bit verbose
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF", "-u", (target.value / "test-reports").toString),  // summarized test output

  //As part of clustering work, after integrating actor message serializer (kryo-akka in our case)
  // an issue was found related to class loading when we run 'sbt test'
  // (which was somehow breaking the deserialization process)
  // so to fix that, had to add below setting
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
)

lazy val packageSettings = Seq (
  maintainer := "Evernym Inc <dev@evernym.com>",
  packageName := s"${name.value}-application",
  (Debian / name) := s"${name.value}-application", // not sure why they don't use packageName debian file name but they *don't*
  packageSummary := "Verity Application",
  packageDescription := "Verity API",
  linuxPackageMappings += {
    val basePackageMapping = Seq(
      assembly.value
        -> s"/usr/lib/${packageName.value}/${packageName.value}-assembly.jar",
      baseDirectory.value / "src" / "main" / "resources" / "systemd" / "systemd.service"
        -> s"/usr/lib/systemd/system/${packageName.value}.service",
      baseDirectory.value / "src" / "debian" / "empty"
        -> s"/etc/verity/${packageName.value}"
    )
    val jars = searchForAdditionalJars(
      (Compile / dependencyClasspath).value,
      agentJars.value
    )
    .map(x => x.copy(_2 = s"/usr/lib/${packageName.value}/${x._2}"))

    packageMapping(basePackageMapping ++ jars: _*)
  },
  linuxPackageMappings += {
    buildPackageMappings(
      s"verity/src/main/resources/debian-package-resources",
      s"/usr/share/${name.value}/${packageName.value}",
      includeFiles = Set.empty,
      replaceFilesIfExists = true
    )
  },
  Compile / resourceGenerators += SourceGenerator.writeVerityVersionConf(version).taskValue,
  Debian / packageArchitecture := "amd64",
  // libvdrtools provides libvdrtools.so
  Debian / debianPackageDependencies ++= Seq(
    "default-jre",
    s"libvdrtools(>= $debPkgDepLibVdrToolsMinVersion)",
  ),
  Debian / debianPackageConflicts := Seq(
    "consumer-agent",
    "enterprise-agent"
  )
)

lazy val protoSources = dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/main")).absolutePaths
lazy val protoTestSources = dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/test")).absolutePaths
lazy val protoBufSettings = Seq(
  // Must set deleteTargetDirectory to false. When set to true (the default) other generated sources in the
  // sourceManaged directory get deleted. For example, version.scala being generated below.
  PB.deleteTargetDirectory := false,

  //this 'PB.includePaths' is to make import works
  //There are used .absolutePaths because after update sbt-protoc to 1.0.6 sbt produce warnings about relative paths
  Compile / PB.includePaths ++= protoSources,
  Compile / PB.targets := Seq(
    scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value
  ),
  Compile / PB.protoSources := dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/main")).absolutePaths,
  Compile / sourceGenerators += SourceGenerator.generateVersionFile(major, minor, patch, build).taskValue,

  // Since sbt-protoc 1.0.1 and later adds PB.protoSources to unmanagedResourceDirectories and unmanagedSourceDirectories
  // we need to exclude all extra dirs that contains .scala files. Same for Test config.
  Compile / unmanagedResourceDirectories --= protoSources,
  Compile / unmanagedSourceDirectories --= protoSources,

  Test / PB.includePaths ++= dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/main")).absolutePaths,
  Test / PB.targets := Seq(
    scalapb.gen(flatPackage = true) -> (Test / sourceManaged).value
  ),
  Test / PB.protoSources := protoTestSources,
  Test / unmanagedResourceDirectories --= protoTestSources,
  Test / unmanagedSourceDirectories --= protoTestSources,
  //
)

// For this really to do its job correctly, it needs the class files from compiling. But since
// coverageExcludedFiles is a SettingKey, requiring compile would annoying. So compile for
// accurate results.
val coverageSettings = Seq(
  coverageExcludedFiles := scoverageFilterProtobufPattern(
    (Compile / classDirectory).value,
    (Test / classDirectory).value
  )
)

val lightbendCommercialSettings = {
    Lightbend.init ++ Seq (
      lightbendCinnamonVer := cinnamonVer,
      resolvers ++= lightbendResolvers.value,
      libraryDependencies ++= lightbendDeps.value,
      agentJars ++= lightbendCinnamonAgentJar.value,
      excludeFilter := lightbendClassFilter.value.map(excludeFilter.value || _).getOrElse(excludeFilter.value)
    )
}

lazy val commonLibraryDependencies = {

  val akkaGrp = "com.typesafe.akka"

  val coreDeps = Seq.apply(

    //akka dependencies
    akkaGrp %% "akka-actor" % akkaVer,
    akkaGrp %% "akka-persistence" % akkaVer,
    akkaGrp %% "akka-cluster-sharding" % akkaVer,
    akkaGrp %% "akka-http" % akkaHttpVer,

    akkaGrp %% "akka-actor-typed" % akkaVer,
    akkaGrp %% "akka-persistence-typed" % akkaVer,
    akkaGrp %% "akka-cluster-sharding-typed" % akkaVer,

    akkaGrp %% "akka-discovery" % akkaVer,

    akkaGrp %% "akka-stream" % akkaVer,
    akkaGrp %% "akka-stream-kafka" % alpAkkaKafkaVer,

    //akka persistence dependencies
    akkaGrp %% "akka-persistence-dynamodb" % dynamoDbPersistence,

    //to be used for event bus
    "io.cloudevents" % "cloudevents-core" % cloudEventsVersion,
    "io.cloudevents" % "cloudevents-json-jackson" % cloudEventsVersion,

    //lightbend akka dependencies
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpAkkaS3Ver,

    "com.lightbend.akka.management" %% "akka-management" % akkaMgtVer,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaMgtVer,
    "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaMgtVer,

    "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaMgtVer,

    //other akka dependencies
    "com.twitter" %% "chill-akka" % "0.10.0",    //serialization/deserialization for akka remoting

    //vdr tools dependencies
    "com.evernym.vdrtools" % "vdr-tools" % vdrtoolsWrapperVer,

    //logging dependencies
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    akkaGrp %% "akka-slf4j" % akkaVer,

    //kamon monitoring dependencies
    "io.kamon" % "kanela-agent" % kanelaAgentVer  % "provided",    //a java agent needed to capture akka related metrics
    "io.kamon" %% "kamon-bundle" % kamonVer,
    "io.kamon" %% "kamon-prometheus" % kamonVer,
    "io.kamon" %% "kamon-datadog" % kamonVer,
    "io.kamon" %% "kamon-jaeger" % kamonVer,

    //message codec dependencies (native classes to json and vice versa) [used by JacksonMsgCodec]
    "com.fasterxml.jackson.datatype" % "jackson-datatype-json-org" % jacksonVer,    //JSONObject serialization/deserialization
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVer,      //Java "time" data type serialization/deserialization
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVer,          //Scala classes serialization/deserialization

    //sms service implementation dependencies
    "com.fasterxml.jackson.jakarta.rs" % "jackson-jakarta-rs-json-provider" % jacksonVer,
    //"com.fasterxml.jackson.jaxrs" % "jackson-jaxrs-json-provider" % jacksonVer,     //used by "BandwidthDispatcher"/"OpenMarketDispatcherMEP" class
    "org.glassfish.jersey.core" % "jersey-client" % "3.0.5"
      excludeAll ExclusionRule(organization = "jakarta.inject"),                      //TODO: (should fix this) excluded to avoid issue found during 'sbt assembly' after upgrading to sbt 1.3.8
    "com.twilio.sdk" % "twilio-java-sdk" % "6.3.0",                                 //used by "TwilioDispatcher" class

    //other dependencies
    "com.github.blemale" %% "scaffeine" % "5.2.0",
    "commons-net" % "commons-net" % "3.8.0",      //used for CIDR based ip address validation/checking/comparision
                                                    // (for internal apis and may be few other places)
    "commons-codec" % "commons-codec" % "1.15",
    "org.msgpack" % "msgpack-scala_2.13.0-M2" % "0.8.13",  //used by legacy pack/unpack operations
    "org.fusesource.jansi" % "jansi" % "2.4.0",    //used by protocol engine for customized logging
    "info.faljse" % "SDNotify" % sdnotifyVer,     //used by app state manager to notify to systemd
    "net.sourceforge.streamsupport" % "java9-concurrent-backport" % "2.0.5",  //used for libvdrtools sync api calls
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    //"org.scala-lang.modules" %% "scala-java8-compat" % "1.0.0",   //commented as seemed not used

    "org.iq80.leveldb" % "leveldb" % "0.12",      //used as alternate StorageAPI to S3

    "org.json" % "json" % "20220320",
  )

  //for macro libraries that are compile-time-only
  val compileTimeOnlyDeps = Seq[ModuleID](
  ).map(_ % COMPILE_TIME_ONLY)

  //test dependencies
  val testDeps = Seq(
    "org.scalatest" %% "scalatest-freespec" % scalatestVer,
    "org.scalatest" %% "scalatest-shouldmatchers" % scalatestVer,
    "org.mockito" %% "mockito-scala-scalatest" % mockitoVer,

    "com.vladsch.flexmark" % "flexmark-all" % flexmarkVer,

    akkaGrp %% "akka-testkit" % akkaVer,
    akkaGrp %% "akka-stream-testkit" % akkaVer,
    akkaGrp %% "akka-persistence-testkit" % akkaVer,
    akkaGrp %% "akka-http-testkit" % akkaHttpVer,
    akkaGrp %% "akka-stream-kafka-testkit" % alpAkkaKafkaVer,
    akkaGrp %% "akka-serialization-jackson" % akkaVer,

    "com.evernym" % "vcx" % vcxWrapperVer,
    "com.evernym.verity" % "verity-sdk" % veritySdkVer
      exclude ("com.evernym.vdrtools", "vdr-tools"),

    "com.dimafeng" %% "testcontainers-scala-kafka" % "0.40.9",

    "org.pegdown" % "pegdown" % "1.6.0",
    "com.goterl" % "lazysodium-java" % "5.1.1",
    "net.java.dev.jna" % "jna" % "5.12.1",

    "net.glxn" % "qrgen" % "1.4", // QR code generator
    "com.google.guava" % "guava" % "31.1-jre",

    //post akka 2.6 upgrade, had to add below test dependencies with given akka http version
    //need to come back to this and see if there is better way to fix it
    akkaGrp %% "akka-http-spray-json" % akkaHttpVer,
    akkaGrp %% "akka-http-xml" % akkaHttpVer,

    "org.apache.httpcomponents" % "httpcore" % "4.4.15",
  ).map(_ % "test")

  coreDeps ++ compileTimeOnlyDeps ++ testDeps
}

lazy val mergeStrategy: PartialFunction[String, MergeStrategy] = {
  case PathList("META-INF", "io.netty.versions.properties")         => MergeStrategy.concat
  case PathList(ps @ _*) if ps.last equals "module-info.class"      => MergeStrategy.concat
  case PathList("systemd", "systemd.service")                       => MergeStrategy.first
  case PathList("mime.types")                                       => MergeStrategy.first
  case PathList(ps @ _*) if ps.last endsWith ".proto"               => MergeStrategy.first
  case PathList("reference.conf")                                   => referenceConfMerge()
  case PathList("cinnamon-reference.conf")                          => MergeStrategy.concat
  case PathList("cinnamon", "instrument", "Instrumentations.class") => MergeStrategy.last
  case PathList(ps @ _*) if ps.last equals "version.conf"           => MergeStrategy.concat
  case s if s.contains("kanela-agent")                              => MergeStrategy.discard
  case s                                                            => MergeStrategy.defaultMergeStrategy(s)
}

/*
Only has an effect for gitlab CI/CD pipeline

The compression of files reduces the granularity of file
timestamps. The truncateStamps will reduce all stamps to second
precision.

This still needs more work. Uncomment the next two lines to enable timestamp truncation in GitLab CICD Pipeline.
See https://evernym.atlassian.net/browse/KAIZ-20 for more information
 */

//Compile / previousCompile ~= Util.truncateStamps
//Test / previousCompile ~= Util.truncateStamps
