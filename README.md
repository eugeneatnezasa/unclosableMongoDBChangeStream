## MongoDB change stream usage (issue was solved, see update info below)

We found that it is not possible to close Mongo Change stream until requested amount of records 
was consumed. In other words, even if we request only on item from mongo and collection is updated 
rarely, and we call watch  multiple times with unsubscribe just after, then we will open and keep 
as many cursors as requested keeping them alive until first event. Which looks like a bug.

I implemented two modes: first to demonstrate exception on immediate unsubscribe. And second
with workaround, when we consume all requested event and unsubscribe just after.   

```bash
$ # run test application with or without exception
$ # mongodb cluster is supposed to be available on standard port on localhost
$ # cluster is required to use mongo change streams
$ sbt run # this will run application in mode with immediate unsubscribe from mongo subscription
$ sbt "run fixed" # this will run application in mode with consuming all requested event first
``` 

## Update from 14.02.2020

Issue was fixed in java async driver part

Issue: https://jira.mongodb.org/browse/JAVA-3487

Java driver version with fix:

```sbt
"org.mongodb" % "mongodb-driver-async" % "3.12.1"
``` 



Our workflow is:
1. We use collection as datastore with external index
2. We monitor collection for changes (using mongo changestreams) to re-index any changed record near real time
3. Once a day we reload all the data and for this purpose we need to stop change stream, reload data, re-index everything and restart change stream
4. We cannot somehow stop underlying change stream cursor (see comments inline in `com.example.MongoDBChangeStream`). 
5. We close akkastream, and then exception is printed (from inside mongodb driver internals). Changestream is closed after that (if we check in mongodb cli)

Application is build on top of akkastreams, so `ChangeStreamObservable` is wrapped into `akkastream.Source` 