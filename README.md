## MongoDB change stream usage

We found that it is not possible to close Mongo Change stream. Here you can find demo of issue we have

```bash
$ # run test application
$ # mongodb cluster is supposed to be available on standard port on localhost
$ # cluster is required to use mongo change streams
$ sbt run
``` 

Our workflow is:
1. We use collection as datastore with external index
2. We monitor collection for changes (using mongo changestreams) to re-index any changed record near real time
3. Once a day we reload all the data and for this purpose we need to stop change stream, reload data, re-index everything and restart change stream
4. We cannot somehow stop underlying change stream cursor (see comments inline in `com.example.MongoDBChangeStream`). 
5. We close akkastream, and then exception is printed (from inside mongodb driver internals). Changestream is closed after that (if we check in mongodb cli)

Application is build on top of akkastreams, so `ChangeStreamObservable` is wrapped into `akkastream.Source` 