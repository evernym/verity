package com.evernym.verity.vdrtools

import java.nio.file.{Path, Paths}

import scala.annotation.tailrec

/*
Facilitates runtime modification of the JNA path

This is used mostly for development and CI/CD pipelines
NOT real application deployments
 */
object JnaPath {
  val sharedLibsDir = "shared-libs"



  // Paths to look for shared lib directories
  val searchPaths: Set[Option[String]] = Set(
    None, // project root directory
    Some("verity"),
    Some("integration-test")
  )

  // If application is running in a build or development env (defined as SBT or IntelliJ
  // augment to include non-packaged managed shared libraries (ex libindy)
  def augmentJnaPath(propKey: String = "jna.library.path"): Unit = {
    augmentJnaWithPaths(findJnaPaths(), propKey)
  }

  def augmentJnaWithPaths(paths: Seq[String], propKey: String = "jna.library.path"): Unit = {
    if(isRecognizedCmd()){
      val currentJnaPath = sys.props.get(propKey)
      val filteredPaths = filterJnaPaths(
        Option(paths).getOrElse(Seq.empty),
        currentJnaPath
      )
      applyNewPaths(
        filteredPaths,
        currentJnaPath,
        propKey
      )
    }
  }

  // Set of partial CMD that are recognized that the application
  // is running in SBT or IntelliJ
  val recognizedCmd: Set[String] = Set(
    "jetbrains.plugins.scala",
    "sbt-launch"
  )

  def isRecognizedCmd(propKey: String = "sun.java.command"): Boolean = {
    // linear search is ok because it only happens a couple of times
    // during startup over a very small set
    sys
      .props
      .get(propKey)
      .flatMap(_.split("\\s").toList.headOption)
      .exists{ x =>
        recognizedCmd.exists(x.contains(_))

      }
  }

  def applyNewPaths(paths: Seq[String], currentJnaPath: Option[String], propKey: String = "jna.library.path"): Unit = {
    val pathsToAdd = Option(paths)
      .getOrElse(Seq.empty)
      .mkString(":")

    val newPropValue = currentJnaPath match {
      case None => pathsToAdd
      case Some("") => pathsToAdd
      case Some(p) => s"$pathsToAdd:$p"
    }
    sys.props += propKey -> newPropValue
  }

  def filterJnaPaths(paths: Seq[String], currentJnaPath: Option[String]): Seq[String] = {
    val jnaPath = currentJnaPath.getOrElse("")
    Option(paths)
      .getOrElse(Seq.empty)
      .filterNot(jnaPath.contains(_))
  }

  def findJnaPaths(cwdPropKey: String = "user.dir"): Seq[String] = {
    val cwd = sys.props.get(cwdPropKey).map(Paths.get(_))
    val firstDirWithTarget = cwd.flatMap(searchTargetFirst)
    val lastDirWithTarget = firstDirWithTarget.flatMap(searchTargetLast)
    lastDirWithTarget.map{ p =>
      searchPaths
        .map(_.map(p.resolve).getOrElse(p))
        .map(_.resolve(s"target/$sharedLibsDir/libs"))
        .filter(_.toFile.isDirectory)
        .map(_.toAbsolutePath.toString)
        .toSeq
    }
      .getOrElse(Seq.empty)
  }

  @tailrec
  private def searchTargetFirst(path: Path): Option[Path] = {
    val searchPath = path.normalize()
    if(searchPath.resolve("target").toFile.isDirectory) {
      Some(searchPath)
    }
    else if (searchPath.getParent == null){
      None
    }
    else {
      searchTargetFirst(searchPath.getParent)
    }
  }

  @tailrec
  private def searchTargetLast(path: Path): Option[Path] = {
    val searchPath = Option(path.normalize().getParent)
    searchPath match {
      case Some(p) =>
        if(p.resolve("target").toFile.isDirectory){
          searchTargetLast(p)
        }
        else {
          Some(path)
        }
      case None => None
    }
  }
}
