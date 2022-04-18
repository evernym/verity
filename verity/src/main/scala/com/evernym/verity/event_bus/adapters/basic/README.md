This is basic version of event bus adapter implementation (which depends on the basic event store implemented in verity)
Below is the configuration required for the basic event store and corresponding basic adapter implementation.


```
verity {
  event-bus {
    basic {
      store {
        http-listener {
          host = "localhost"
          port = 1233
        }
      }
      consumer {
        id = "verity"
        topics = ["topic1", "topic2"]
        http-listener {
          host = "localhost"
          port = 1233
        }
      }
    }
  }    
}
```