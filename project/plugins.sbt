logLevel := Level.Warn

addSbtPlugin("com.eed3si9n"           % "sbt-assembly" % "1.1.0")
addSbtPlugin("com.github.sbt"       % "sbt-native-packager" % "1.9.7")
addSbtPlugin("net.virtual-void"       % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.typesafe.sbt"       % "sbt-git" % "1.0.2")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.1.1")

addSbtPlugin("org.scoverage"          %  "sbt-scoverage" % "1.9.3")