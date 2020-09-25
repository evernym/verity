import java.io.File

def findDirsContainingProtos(root: File): Seq[File] = {
  val children = root.listFiles().toList
  val dirs = children.filter(_.isDirectory)
  val protodirs = dirs.flatMap(findDirsContainingProtos)
  val hasProtos = children.exists(f => f.isFile && f.getName.endsWith(".proto"))
  if (hasProtos) root :: protodirs else protodirs
}

findDirsContainingProtos(new File("dev/evernym/gitlab/agency/verity/src/test"))
