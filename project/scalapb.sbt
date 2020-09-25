addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.23")

//Warning: don't upgrade version unless we solve java serialization issue with MultiEvent
//for existing persisted connecting protocols
//Ticket: VE-1107
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.9.0"
