//package com.evernym.verity.integration.base.verity_provider.node.process
//
//import akka.cluster.MemberStatus
//import com.evernym.verity.integration.base.verity_provider.PortProfile
//import com.evernym.verity.integration.base.verity_provider.node.VerityNode
//import com.evernym.verity.integration.base.verity_provider.node.local.LocalVerity.buildCustomVerityConfigOnly
//import com.evernym.verity.integration.base.verity_provider.node.local.ServiceParam
//import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
//
//import java.io._
//import java.nio.file.{Path, Paths}
//import scala.sys.process._
//import scala.language.postfixOps
//
//case class VerityProcessNode(runFromJarAtPath: String,
//                             tmpDirPath: Path,
//                             appSeed: String,
//                             portProfile: PortProfile,
//                             otherNodeArteryPorts: Seq[Int],
//                             serviceParam: Option[ServiceParam],
//                             overriddenConfig: Option[Config]) extends VerityNode {
//
//  var verityPID: Int = -1
//  var isAvailable: Boolean = false
//  val projectDir: Path = Paths.get(".").toAbsolutePath.normalize()
//  lazy val scriptDir = "verity/src/test/scala/com/evernym/verity/integration/base/verity_provider/node/remote"
//  lazy val START_SCRIPT_PATH: Path = projectDir.resolve(s"$scriptDir/start.sh")
//  lazy val STOP_SCRIPT_PATH: Path = projectDir.resolve(s"$scriptDir/stop.sh")
//
//  val nodeDirPath = createDir(s"$tmpDirPath/${portProfile.http}")
//
//  override def start(): Unit = {
//    writeConfigFile()
//    val cmd = s"""bash $START_SCRIPT_PATH $runFromJarAtPath $nodeDirPath"""
//    val pc = Process(cmd, None)
//    val pid =  pc.!!
//    verityPID = pid.replaceAll("\\n", "").toInt
//  }
//
//  override def stop(): Unit = {
//    val cmd = s"""bash $STOP_SCRIPT_PATH $verityPID"""
//    val pc = Process(cmd, None)
//    pc.!!
//
//  }
//
//  override def checkIfNodeIsUp(otherNodesStatus: Map[VerityNode, List[MemberStatus]]): Boolean = {
//    Thread.sleep(5000)
//    true    //TODO: come back to this
//  }
//
//  private def runScript(scriptPath: Path): String = {
//    s"""bash $scriptPath $runFromJarAtPath"""  !!
//  }
//
//  private def writeConfigFile(): Unit = {
//
//    val config =
//      ConfigFactory.parseString(
//        s"""
//          |verity.lib-indy.ledger.genesis-txn-file-location = "$projectDir/target/genesis.txt"
//          |verity.lib-indy.library-dir-location = "/var/lib/indy"
//          |""".stripMargin
//      ).withFallback(
//        ConfigFactory
//          .parseFile(new File(s"$projectDir/verity/src/test/resources/application.conf")
//          )
//      ).withFallback(buildCustomVerityConfigOnly(verityNodeParam))
//       .resolve()
//
//    val cro = ConfigRenderOptions.defaults().setOriginComments(false)
//    val keysToWrite = Seq("verity", "akka")
//    val configStr = keysToWrite.map { key =>
//      s""""$key"
//        ${config.getObject(key).render(cro)}
//      """.stripMargin
//    }.mkString("\n\n")
//    writeFile(s"$nodeDirPath/application.conf", configStr)
//  }
//
//  private def createDir(atPath: String): Path = {
//    val path = Path.of(atPath)
//    val file = new File(atPath)
//    file.mkdir()
//    file.toPath
//  }
//
//  private def writeFile(atPath: String, content: String): Unit = {
//    new PrintWriter(atPath) { write(content); close() }
//  }
//
//  start()
//}
