import java.nio.file.{Files, Path, Paths}

import sbt._
import sbt.internal.util.ManagedLogger

import scala.sys.process._

object SharedLibrary {
  val managedSharedLibTrigger: String = "VERITY_MANAGE_SHARED_LIBS"
  /*
  This code expects and requires a debian package mangers is on the system (dpkg and apt-get)
  It also expects that the required repos for given share libraries are available
   */
  sealed trait Lib {
    def packageName: String
    def packageVersion: String
    def libraryName: String
  }

  // For cases where the library and package share the same name
  case class LibPack(packageName: String, packageVersion: String) extends Lib {
    val libraryName: String = s"$packageName.so"
  }

  val updateSharedLibraries = taskKey[Unit]("Update Shared Libraries")

  val packageSubDir = "pkg"
  val libsSubDir = "libs"

  def defaultUpdateSharedLibraries(libs: Seq[Lib],
                                   shareLibTarget: Path,
                                   logger: ManagedLogger): Unit = {
    if(!sys.env.contains(managedSharedLibTrigger)) return

    // check that required commands are available
    try {
      "dpkg --version".!!
      "apt-get -v".!!
    }
    catch{
      case e: Exception => throw new Exception("Required commands not found! must have dpkg and apt-get", e)
    }

    // downloads and unpacks given shared libraries
    libs.foreach { l =>
      downloadSharedLibrary(
        l.packageName,
        l.libraryName,
        l.packageVersion,
        shareLibTarget,
        logger
      )
    }

    // very simple check of dependencies
    // if library is not libindy, expects it only dependency to be libindy (this is true as of now)
    // if the library is libindy, check that its dependencies are on the box
    libs.foreach{ l =>
      checkDeps(l.packageName, l.packageVersion, shareLibTarget)
    }
  }

  private val depsLineRegex = "Depends: (.*)".r()

  private def checkDeps(packageName: String, packageVersion: String, dest: Path): Unit = {
    val deps = findDeps(packageName, packageVersion, dest)
    packageName match {
      case "libindy" =>
        deps.foreach{ d =>
          try {
            Seq(
              "dpkg",
              "-l",
              d.trim
            ).!!
          }
          catch {
            case e:RuntimeException => throw new Exception(s"Dependency '$d' NOT found for libindy", e)
          }
        }
      case _ =>
        if(!(deps.length == 1 && deps.head.contains("libindy"))) {
          throw new Exception(s"'$packageName' has a dependency that is not libindy -- currently not allowed")
        }
    }
  }

  /*
  Pulling dependencies from package
   */
  private def findDeps(packageName: String, packageVersion: String, dest: Path): Seq[String] = {
    val packageDest = dest.resolve(packageSubDir)
    val controlFile = Process(
      Seq(
        "dpkg-deb",
        "-I",
        s"${packageName}_${packageVersion}_amd64.deb"
      ),
      packageDest.toFile
    )
    .!!

    depsLineRegex
      .findFirstMatchIn(controlFile)
      .map(_.group(1))
      .getOrElse(throw new Exception(s"Unable to check dependencies for $packageName"))
      .split(",")
      .toSeq
  }


  private def logExceptions(logger: ManagedLogger)(f: => Any): Unit = {
    try {
      f
    }
    catch {
      case e: Exception =>
        logger.error(e.toString)
        e.printStackTrace()
        throw e
    }

  }

  /*
  Downloads package via `apt`, unpackage the package and extract target library
   */
  private def downloadSharedLibrary(packageName: String,
                            libName: String,
                            packageVersion: String,
                            dest: Path,
                            logger: ManagedLogger): Unit = logExceptions(logger) {
    val packageDest = dest.resolve(packageSubDir)
    val packageUnpackedDest = packageDest.resolve(s"$packageName-$packageVersion-unpacked")
    val libDest = dest.resolve(libsSubDir)
    val libVersionedDest = libDest.resolve(s"$libName.$packageVersion")
    if (libVersionedDest.toFile.exists()) {
      logger.debug(s"The lib '$libName' in the '$packageName' for version '$packageVersion' already exists and has already been downloaded.")
    } else {
      logger.info(s"Downloading '$libName' from the package '$packageName' at version '$packageVersion' via Apt")
      logger.debug(s"Targeting '$packageDest' for downloaded package")

      logger.debug(s"Making needed directories in '$dest'")
      makeDir(dest)
      makeDir(packageDest)
      makeDir(packageUnpackedDest)
      makeDir(libDest)

      logger.debug("Downloading package via `apt-get`")
      Process(
        Seq(
          "apt-get",
          "download",
          s"$packageName=$packageVersion"
        ),
        packageDest.toFile
      ).!!

      logger.debug("Unzipping package via `dpkg-deb`")
      Process(
        Seq(
          "dpkg-deb",
          "-x",
          s"${packageName}_${packageVersion}_amd64.deb",
          packageUnpackedDest.toString
        ), packageDest.toFile
      ).!!

      val foundLibFilePath = Process(
        Seq(
          "find",
          packageUnpackedDest.toString,
          "-name",
          s"$libName*")
      )
      .!!
      .trim

      if(foundLibFilePath.isEmpty) {
        throw new Exception(s"Unable to find '$libName' in $packageName for $packageVersion -- cannot continue")
      }

      val libFilePath = Paths.get(foundLibFilePath)


      logger.debug(s"Coping found library to versioned file --'${libVersionedDest.toString}'")
      Files.copy(libFilePath, libVersionedDest)
    }


    val commonFileDest = libDest.resolve(libName)
    logger.debug(s"Coping library to file --'${commonFileDest.toString}'")
    Files.deleteIfExists(commonFileDest)
    Files.copy(libVersionedDest,commonFileDest)
  }

  private def makeDir(dir: Path): Unit = {
    val dirFile = dir.toFile
    if(dirFile.exists()) {
      if(!dirFile.isDirectory){
        throw new Exception(s"'$dir' MUST be a directory")
      }
    }
    else {
      if(!dir.toFile.mkdirs()){
        throw new Exception(s"Unable to create directors for '$dir'")
      }
    }
  }
}
