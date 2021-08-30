logLevel := Level.Warn

addSbtPlugin("com.eed3si9n"           % "sbt-assembly" % "1.0.0")
addSbtPlugin("com.typesafe.sbt"       % "sbt-native-packager" % "1.8.1")
addSbtPlugin("net.virtual-void"       % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.typesafe.sbt"       % "sbt-git" % "1.0.1")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.1.1")

addSbtPlugin("org.scoverage"          %  "sbt-scoverage" % "1.8.2")