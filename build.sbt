/*
Scala's incremental compilation depends on file timestamps with millisecond resolution. Compressed artifacts passed
between jobs in a job's pipeline drop millisecond resolution on file stimestamps.

Use truncateStamps to allow compressed artifacts in the CI/CD pipeline passed from one stage to another in the
      CI/CD pipeline to remain valid (no need to recompile).

import Util.amGitlabCI

See https://docs.gitlab.com/ce/ci/caching/ for details and/or possible alternatives.
*/
import DevEnvironment.DebianRepo
import DevEnvironmentTasks.{envRepos, jdkExpectedVersion}
import SharedLibrary.{NonMatchingDistLib, NonMatchingLib}
import SharedLibraryTasks.{sharedLibraries, updateSharedLibraries}
import Util.{addDeps, buildPackageMappings, cloudrepoPassword, cloudrepoUsername, conditionallyAddArtifact, dirsContaining, findAdditionalJars, referenceConfMerge}
import Version._
import sbt.Keys.{libraryDependencies, organization, update}
import sbtassembly.AssemblyKeys.assemblyMergeStrategy
import sbtassembly.MergeStrategy

import scala.language.postfixOps

enablePlugins(JavaAppPackaging)

ThisBuild / jdkExpectedVersion := "1.8"

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
val libIndyVer = "1.15.0~1618"
val libMysqlStorageVer = "0.1.11"
val sharedLibDeps = Seq(
  NonMatchingDistLib("libindy", libIndyVer, "libindy.so"),
  NonMatchingDistLib("libnullpay", libIndyVer, "libnullpay.so"),
  NonMatchingLib("libmysqlstorage", libMysqlStorageVer, "libmysqlstorage.so"),
  NonMatchingLib("libvcx", "0.10.1-bionic~1131", "libvcx.so"), // For integration testing ONLY
)

//deb package dependencies versions
val debPkgDepLibIndyMinVersion = libIndyVer
val debPkgDepLibMySqlStorageVersion = libMysqlStorageVer

//dependency versions
val indyWrapperVer  = "1.15.0-dev-1618"

val akkaVer         = "2.6.10"
val akkaHttpVer     = "10.2.2"
val akkaMgtVer      = "1.0.9"
val alpAkkaVer      = "2.0.2"
val kamonVer        = "2.1.9"
val kanelaAgentVer  = "1.0.7"
val jacksonVer      = "2.11.1"    //TODO: incrementing to latest version (2.12.0) was causing certain unexpected issues
                                  // around base64 decoding etc, should look into it.
val sdnotifyVer     = "1.3"

//test dependency versions
val scalatestVer    = "3.2.0"
val mockitoVer      = "1.14.8"
val veritySdkVer    = "0.4.5-77b158ab"
val vcxWrapperVer   = "0.10.1.1131"

// compiler plugin versions
val silencerVersion = "1.7.1"

// a 'compileonly' configuration (see https://stackoverflow.com/questions/21515325/add-a-compile-time-only-dependency-in-sbt#answer-21516954)
val COMPILE_TIME_ONLY = "compileonly"
val CompileOnly = config(COMPILE_TIME_ONLY)

val majorNum = "0"
val minorNum = "4"

// I'm not sure why setting this keys don't resolve in all
// other scopes but it does not so we re-resolve it commonSettings
ThisBuild / major := majorNum
ThisBuild / minor := minorNum
ThisBuild / patch := patchNum(
  git.gitHeadCommitDate.value,
  git.gitHeadCommit.value,
  git.gitUncommittedChanges.value
)
version := s"${major.value}.${minor.value}.${patch.value}"
maintainer := "Evernym Inc <dev@evernym.com>"

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
    libraryDependencies ++= addDeps(commonLibraryDependencies, Seq("scalatest_2.12"),"it,test"),
    // Conditionally download an unpack shared libraries
    update := update.dependsOn(updateSharedLibraries).value
  )

lazy val integrationTests = (project in file("integration-tests"))
  .settings(
    name := "integration-tests",
    settings,
    protoBufSettings,
    libraryDependencies ++= addDeps(
      commonLibraryDependencies ++ Seq.apply ("org.iq80.leveldb" % "leveldb" % "0.11" ),
      Seq("scalatest_2.12"),
      "test"),

    // Do not publish any artifacts created during the Compile or Test tasks.
    // Suppress publishing of pom, docs, source and bin jar.
    // The only artifact that must be published is added when calling conditionallyAddArtifact below.
    publishArtifact in Compile := false,
    publishArtifact in Test := false,

    // Assembly task in this sub-project must assemble a jar containing the test classes
    // Include Test classes in assembled jar when integrationTests/assembly is run
    Project.inConfig(Test)(baseAssemblySettings),
    assembly := (assembly in Test).value,
    // Merge strategy during assembly
    assemblyMergeStrategy in (Test, assembly) := mergeStrategy,
    // Only add the assembly artifact if a username and password are defined for the repo.
    // The assumption here is that developers will be creating SNAPSHOTs in their dev
    // environments and will NOT have a username and password defined. Therefore, SNAPSHOTS
    // will effectively be excluded if a integrationTests/test:publish is run from a dev environment.
    conditionallyAddArtifact(artifact in (Test, assembly), assembly in Test),
    // Do NOT run tests (integration in this case) during assembly
    test in assembly := {},
    test in (Test, assembly) := {},
    publishTo := {
      val nexus = "https://evernym.mycloudrepo.io/repositories/"
      Some("releases"  at nexus + "evernym-dev")
    },
    credentials += Credentials(
      "evernym.mycloudrepo.io",
      "evernym.mycloudrepo.io",
      cloudrepoUsername,
      cloudrepoPassword
    ),
    publishMavenStyle := true
  ).dependsOn(verity % "test->test; compile->compile")

lazy val settings = Seq(
  organization := "com.evernym",
  version := s"${major.value}.${minor.value}.${patch.value}",
  scalaVersion := "2.12.12",
  scalacOptions := Seq(
    "-feature",
    "-unchecked",
    "-deprecation",
    "-encoding",
    "utf8",
    "-Xmax-classfile-name",
    "128",
    "-Xfatal-warnings",
    "-P:silencer:pathFilters=.*/tictactoe/Role.scala;.*/deaddrop/Role.scala"
  ),
  // ComilerPlugin to allow suppression of a few warnings so we can get a clean build
  libraryDependencies ++= Seq(
    compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
    "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
  ),
  resolvers += Resolver.mavenLocal,
  resolvers += "Lib-indy" at "https://repo.sovrin.org/repository/maven-public",
  resolvers += "MsgPack" at "https://dl.bintray.com/velvia/maven",
  resolvers += "libvcx" at "https://evernym.mycloudrepo.io/public/repositories/libvcx-java",
  resolvers += "evernym-dev" at "https://evernym.mycloudrepo.io/public/repositories/evernym-dev/",
  resolvers += Resolver.bintrayRepo("bfil", "maven"),

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
  ThisBuild / scapegoatVersion := "1.3.9",
) ++ Defaults.itSettings

lazy val testSettings = Seq (
  //TODO: with sbt 1.3.8 made below test report settings breaking, shall come back to this
  //Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-h", s"target/test-reports/$projectName"),
  //Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-o"),             // standard test output, a bit verbose
  //Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oNCXEHLOPQRM"),  // summarized test output

  //As part of clustering work, after integrating actor message serializer (kryo-akka in our case)
  // an issue was found related to class loading when we run 'sbt test'
  // (which was somehow breaking the deserialization process)
  // so to fix that, had to add below setting
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
)

lazy val packageSettings = Seq (
  maintainer := "Evernym Inc <dev@evernym.com>",
  packageName := s"${name.value}-application",
  name in Debian := s"${name.value}-application", // not sure why they don't use packageName debian file name but they *don't*
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
    val additionalJars = findAdditionalJars(
      (assembly / externalDependencyClasspath).value,
      s"/usr/lib/${packageName.value}",
      Seq("kanela-agent")
    )
    println(basePackageMapping)
    packageMapping(basePackageMapping ++ additionalJars: _*)
  },
  linuxPackageMappings += {
    buildPackageMappings(s"verity/src/main/resources/debian-package-resources",
      s"/usr/share/${name.value}/${packageName.value}",
      includeFiles = confFiles, replaceFilesIfExists = true)
  },
  Debian / packageArchitecture := "amd64",
  // libindy provides libindy.so
  Debian / debianPackageDependencies ++= Seq(
    "default-jre",
    s"libindy(>= $debPkgDepLibIndyMinVersion)",
    s"libnullpay(>= $debPkgDepLibIndyMinVersion)",  // must be the same version as libindy
    s"libmysqlstorage(=$debPkgDepLibMySqlStorageVersion)" //temporary pinning it to specific version until latest version gets fixed
  ),
  Debian / debianPackageConflicts := Seq(
    "consumer-agent",
    "enterprise-agent"
  )
)

lazy val commonLibraryDependencies = {

  val akkaGrp = "com.typesafe.akka"

  val coreDeps = Seq.apply(

    //akka dependencies
    akkaGrp %% "akka-actor" % akkaVer,
    akkaGrp %% "akka-persistence" % akkaVer,
    akkaGrp %% "akka-cluster-sharding" % akkaVer,
    akkaGrp %% "akka-http" % akkaHttpVer,

    //akka persistence dependencies
    akkaGrp %% "akka-persistence-dynamodb" % "1.1.1",

    //lightbend akka dependencies
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpAkkaVer,
    "com.lightbend.akka.management" %% "akka-management" % akkaMgtVer,                //not using as such
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaMgtVer,   //not using as such

    //other akka dependencies
    "com.twitter" %% "chill-akka" % "0.9.5",    //serialization/deserialization for akka remoting

    //hyper-ledger indy dependencies
    "org.hyperledger" % "indy" % indyWrapperVer,

    //logging dependencies
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    akkaGrp %% "akka-slf4j" % akkaVer,

    //message codec dependencies (native classes to json and vice versa) [used by JacksonMsgCodec]
    "com.fasterxml.jackson.datatype" % "jackson-datatype-json-org" % jacksonVer,    //JSONObject serialization/deserialization
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVer,      //Java "time" data type serialization/deserialization
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVer,          //Scala classes serialization/deserialization

    //sms service implementation dependencies
    "com.fasterxml.jackson.jaxrs" % "jackson-jaxrs-json-provider" % jacksonVer,     //used by "BandwidthDispatcher"/"OpenMarketDispatcherMEP" class
    "org.glassfish.jersey.core" % "jersey-client" % "2.25"                          //used by "BandwidthDispatcher"/"OpenMarketDispatcherMEP" class
      excludeAll ExclusionRule(organization = "javax.inject"),                      //TODO: (should fix this) excluded to avoid issue found during 'sbt assembly' after upgrading to sbt 1.3.8
    "com.twilio.sdk" % "twilio-java-sdk" % "6.3.0",                                 //used by "TwilioDispatcher" class

    //kamon monitoring dependencies
    "io.kamon" % "kanela-agent" % kanelaAgentVer,    //a java agent needed to capture akka related metrics

    "io.kamon" %% "kamon-bundle" % kamonVer,
    "io.kamon" %% "kamon-prometheus" % kamonVer,
    "io.kamon" %% "kamon-jaeger" % "2.1.2",

    //other dependencies
    "com.github.blemale" %% "scaffeine" % "4.0.2",
    "commons-net" % "commons-net" % "3.7.2",      //used for CIDR based ip address validation/checking/comparision
                                                    // (for internal apis and may be few other places)
    "commons-codec" % "commons-codec" % "1.15",
    "org.velvia" %% "msgpack4s" % "0.6.0",        //used by legacy pack/unpack operations
    "org.fusesource.jansi" % "jansi" % "1.18",    //used by protocol engine for customized logging
    "info.faljse" % "SDNotify" % sdnotifyVer,     //used by app state manager to notify to systemd
    "net.sourceforge.streamsupport" % "java9-concurrent-backport" % "1.1.1",  //used for libindy sync api calls
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    //"org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",   //commented as seemed not used
  )

  //for macro libraries that are compile-time-only
  val compileTimeOnlyDeps = Seq[ModuleID](
  ).map(_ % COMPILE_TIME_ONLY)

  //test dependencies
  val testDeps = Seq(
    "org.scalatest" %% "scalatest-freespec" % scalatestVer,
    "org.scalatest" %% "scalatest-shouldmatchers" % scalatestVer,
    "org.mockito" %% "mockito-scala-scalatest" % mockitoVer,

    akkaGrp %% "akka-actor-typed" % akkaVer,
    akkaGrp %% "akka-persistence-typed" % akkaVer,
    akkaGrp %% "akka-cluster-sharding-typed" % akkaVer,

    akkaGrp %% "akka-testkit" % akkaVer,
    akkaGrp %% "akka-persistence-testkit" % akkaVer,
    akkaGrp %% "akka-http-testkit" % akkaHttpVer,
    akkaGrp %% "akka-serialization-jackson" % akkaVer,

    "org.iq80.leveldb" % "leveldb" % "0.11",      //to be used in E2E tests
    "org.pegdown" % "pegdown" % "1.6.0",
    "org.abstractj.kalium" % "kalium" % "0.8.0",  // java binding for nacl

    "com.evernym.verity" % "verity-sdk" % veritySdkVer
      exclude ("org.hyperledger", "indy"),

    "net.glxn" % "qrgen" % "1.4", // QR code generator
    "com.google.guava" % "guava" % "28.1-jre",

    "com.evernym" % "vcx" % vcxWrapperVer,

    //post akka 2.6 upgrade, had to add below test dependencies with given akka http version
    //need to come back to this and see if there is better way to fix it
    akkaGrp %% "akka-http-spray-json" % akkaHttpVer,
    akkaGrp %% "akka-http-xml" % akkaHttpVer,

  ).map(_ % "test")

  coreDeps ++ compileTimeOnlyDeps ++ testDeps

}

lazy val protoBufSettings = Seq(
  // Must set deleteTargetDirectory to false. When set to true (the default) other generated sources in the
  // sourceManaged directory get deleted. For example, version.scala being generated below.
  PB.deleteTargetDirectory := false,

  //this 'PB.includePaths' is to make import works
  Compile / PB.includePaths ++= dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/main")),
  Compile / PB.targets := Seq(
    scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value
  ),
  Compile / PB.protoSources := dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/main")),
  Compile / sourceGenerators += SourceGenerator.generateVersionFile(major, minor, patch).taskValue,

  Test / PB.includePaths ++= dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/main")),
  Test / PB.targets := Seq(
    scalapb.gen(flatPackage = true) -> (Test / sourceManaged).value
  ),
  Test / PB.protoSources := dirsContaining(_.getName.endsWith(".proto"))(directory=file("verity/src/test")),
//
) ++ Project.inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings)

lazy val confFiles = Set (
  "akka.conf",
  "dynamodb.conf",
  "lib-indy.conf",
  "logback.xml",
  "salt.conf",
  "secret.conf",
  "sms-client.conf",
  "sms-server.conf",
  "url-mapper-client.conf",
  "metrics.conf",
  "resource-usage-rule.conf",
  "wallet-storage.conf",
  "push-notif.conf",
  "url-mapper-server.conf",
  "alpakka.s3.conf",
  "application.conf"
)

lazy val mergeStrategy: PartialFunction[String, MergeStrategy] = {
  case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.concat
  case PathList(ps @ _*) if ps.last equals "module-info.class"  => MergeStrategy.concat
  case PathList("systemd", "systemd.service")                   => MergeStrategy.first
  case PathList("mime.types")                                   => MergeStrategy.first
  case PathList(ps @ _*) if ps.last endsWith ".proto"           => MergeStrategy.first
  case PathList("reference.conf")                               => referenceConfMerge()
  case s if confFiles.contains(s)                               => MergeStrategy.discard
  case s                                                        => MergeStrategy.defaultMergeStrategy(s)
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
