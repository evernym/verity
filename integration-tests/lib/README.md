To collect any akka metrics, a kanela-agent jar file is needed to be provided as a '-javaagent' parameter in 'java' command during starting of verity process.

Search for 'startCmd' text in 'start.sh' file to know where it is being used

NOTE: This jar file is only used in integration tests run. We may want to find out other better way
to get this working without putting that jar in the lib folder.