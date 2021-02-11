# Msg tracing

Helper classes to make msg tracing recording/capturing easy as it flows through the system.
We start tracing when verity http endpoint receives a request (packed/rest) (a unique request id is generate at this stage)
And then as it reaches to different stages, we update tracking information (like msg type etc)
And when finally that msg is sent (either to edge agent to pairwise their agent), we record it in Kamon.
