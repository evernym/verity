import java.io.File
import java.nio.file.Files
import java.util.Optional
import com.typesafe.sbt.packager.linux.LinuxPackageMapping
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport.packageMapping
import sbt.Def.Classpath
import sbt.{file, _}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.{Analysis, LastModified, Stamps}
import sbtassembly.MergeStrategy
import xsbti.compile.analysis._
import xsbti.compile.{CompileAnalysis, PreviousResult}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, MILLISECONDS, SECONDS, _}
import scala.language.postfixOps

object Util {
  lazy implicit val inGitlabCI: Boolean = sys.env.contains("CI")

    /* Scala's incremental compilation depends on file timestamps with millisecond resolution.
     Compressed artifacts drop millisecond resolution on file stimestamps. Use truncateStamps
     to allow compressed artifacts in the CI/CD pipeline passed from one stage to another in
     the CI/CD pipeline to remain valid (no need to recompile).
     Source: https://github.com/sbt/sbt/issues/4168#issuecomment-417658294
   */
  def truncateStamps(cur: PreviousResult)(implicit shouldTruncate: Boolean): PreviousResult = {
    if (!shouldTruncate) {
      cur
    } else {
      println("Truncating timestamps...")
      def truncateMtime(s: Stamp): Stamp = s match {
        case mtime: LastModified =>
          val truncated = SECONDS.toMillis(MILLISECONDS.toSeconds(mtime.value))
          new LastModified(truncated)
        case other => other
      }

      def truncate(stamps: Stamps): Stamps = {
        Stamps(
          stamps.getAllProductStamps.asScala.toMap.mapValues(truncateMtime),
          stamps.getAllSourceStamps.asScala.toMap.mapValues(truncateMtime),
          stamps.getAllLibraryStamps.asScala.toMap.mapValues(truncateMtime)
        )
      }

      val newAnalysis:Optional[CompileAnalysis] = cur.analysis().asScala.map {
        case a: Analysis => a.copy(stamps = truncate(a.stamps)): CompileAnalysis
      }.asJava

      cur.withAnalysis(newAnalysis)
    }
  }

  def buildPackageMappings(sourceDir: String, targetDir: String,
                           includeFiles: Set[String] = Set.empty,
                           excludeFiles: Set[String] = Set.empty,
                           replaceFilesIfExists: Boolean = false): LinuxPackageMapping = {
    val d = new File(sourceDir)
    val fms = if (d.exists) {
      val files = d.listFiles.filter { f =>
        f.isFile &&
          (includeFiles.isEmpty || includeFiles.contains(f.name)) &&
          (excludeFiles.isEmpty || ! excludeFiles.contains(f.name))
      }.toSeq

      files.map { f =>
        (file(s"$sourceDir/${f.name}"), s"$targetDir/${f.name}")
      }
    } else Seq.empty
    val r = packageMapping(fms: _*)
    if (replaceFilesIfExists) r else r.withConfig("noreplace")
  }

  def dirsContaining(filter: File => Boolean)(directory: File): Seq[File] = {
    val children = Option(directory.listFiles())
      .getOrElse(throw new RuntimeException(s"directory $directory does not exist"))
      .toList
    val dirs = children.filter(_.isDirectory)
    val childDirs = dirs.flatMap(dirsContaining(filter))
    if (children.exists(f => f.isFile && filter(f))) directory :: childDirs  else childDirs
  }

  def searchForAdditionalJars(dependencies: Classpath, jarNames: Seq[String]): Seq[(File, String)] = {
    val depLibs = dependencies.map(_.data).filter(f => ClasspathUtil.isArchive(f.toPath))
    jarNames
      .map { jarName =>
        depLibs.find(_.getName.contains(jarName))
          .map{ foundJar =>
            (foundJar, s"$jarName.jar")
          }
          .getOrElse(throw new Exception(s"Unable to find requested additional jar: '$jarName'"))
      }
  }

  def now: Duration = System.nanoTime().nanos

  def awaitCond(p: => Boolean, max: Duration, interval: Duration = 100 millis, noThrow: Boolean = false): Boolean = {
    val stop = now + max

    @tailrec
    def poll(): Boolean = {
      if (!p) {
        val toSleep = stop - now
        if (toSleep <= Duration.Zero) {
          if (noThrow) false
          else throw new AssertionError(s"timeout $max expired")
        } else {
          Thread.sleep((toSleep min interval).toMillis)
          poll()
        }
      } else true
    }

    poll()
  }

  /*
  There are several reference.conf files from different libraries. This strategy merges them together by
  concatenating them together. But we want the reference.conf from the application to be concatenated last
  so it can override other configuration settings.
   */
  def referenceConfMerge() = new MergeStrategy {
    val name = "referenceConfMerge"
    def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
      MergeStrategy.concat.apply(
        tempDir,
        path,
        files
          .partition { x =>
            val allBytes = new String(Files.readAllBytes(x.toPath))
            val substrLen = if (allBytes.length < 200) allBytes.length else 200
            allBytes
              .substring(0, substrLen)
              .contains("Verity Application Reference Config File")
          }
          .some
          .map { x =>
            if (x._1.length != 1) throw new Exception(s"Did not find application reference.conf -- MUST EXIST (${x._1.length})")
            x._2 ++ x._1
          }
          .getOrElse(throw new Exception("Unable to merge reference.conf file"))
      )
    }
  }
}
