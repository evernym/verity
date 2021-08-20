logLevel := Level.Warn

addSbtPlugin("com.eed3si9n"           % "sbt-assembly" % "1.0.0")
addSbtPlugin("com.typesafe.sbt"       % "sbt-native-packager" % "1.8.1")
addSbtPlugin("net.virtual-void"       % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.typesafe.sbt"       % "sbt-git" % "1.0.1")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.1.1")

//addSbtPlugin("com.github.sbt"         % "sbt-jacoco" % "3.3.0")

//addSbtPlugin("com.lightbend.cinnamon" % "sbt-cinnamon" % "2.16.1")