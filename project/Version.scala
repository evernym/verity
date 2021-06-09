import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset, ZonedDateTime}

import sbt.KeyRanks.BSetting
import sbt.settingKey

object Version {
  val TIME_OFFSET = 1500000000 //Do not change this with out really thinking about it

  val major = settingKey[String]("semver major version").withRank(BSetting)
  val minor = settingKey[String]("semver minor version").withRank(BSetting)
  val patch = settingKey[String]("semver patch version").withRank(BSetting)
  val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
  val currentZonedDateTime = ZonedDateTime.now()

//  def currentVersion: Seq[Setting[_]] = {
//    major := "0"
//    minor := "4"
//    patch := patchNum(
//      git.gitHeadCommitDate.value,
//      git.gitHeadCommit.value,
//      git.gitUncommittedChanges.value
//    )
//    version := s"${major.value}.${minor.value}.${patch.value}"
//  }

  private def shortHash(commitHash: Option[String]): String = {
    commitHash.get.substring(0, 7)
  }

  private def buildTimeNum(commitDate: Option[String]): String = {
    val epoch = LocalDateTime.parse(
      commitDate.get, dateTimeFormatter
    ).atZone(ZoneOffset.UTC).toInstant.toEpochMilli / 1000

    s"${epoch - TIME_OFFSET}"
  }

  def patchNum(commitDate: Option[String], commitHash: Option[String], uncommitChanges: Boolean): String = {
    if (uncommitChanges || !sys.env.contains("CI")) {
      // Always use SNAPSHOTs when producing artifacts outside of gitlab pipelines
      if (sys.env.contains("DEVLAB")) {
        // A debian package DOES NOT get overwritten when posting a debian to the Aptly 'repo' container in devlab
        // if the version already exists in the repo. When a developer sets the "DEVLAB" environment variable, use
        // "now()" instead of commitDate to produce a unique (like a build number), newer version for each packaged
        // artifact. Apt will then see that an upgrade is available and Verity devlab nodes can be patched/updated
        // to include features/fixes that need to be tested in a devlab environment before they can/should be
        // promoted to team/QA/production environments. This logic will NOT execute in gitlab pipelines, because
        // gitlab always builds from fresh clones.
        val snapshotTimestamp = buildTimeNum(Option(currentZonedDateTime.format(dateTimeFormatter)))
        s"0-SNAPSHOT.${snapshotTimestamp}"
      } else {
        "0-SNAPSHOT"
      }
    }
    else {
      s"${buildTimeNum(commitDate)}.${shortHash(commitHash)}"
    }
  }
}
