# Replication of Scala 3 `lazy val` issue with Java Serialization

Run in this order:
1. `sbt "run leader"`, wait for `Waiting for cluster to form up...` log message
2. `sbt "run worker"`, it will choke on one of the first messages
3. kill leader !important!
4. run `jps | grep ClusterApp | cut -d ' ' -f 1 | xargs -I {} jstack {} | grep bomb -B 9 -A 23` to see thread hanging on CountDownLatch#await()
5. add `@transient` to `lazy val bomb: String =`
6. repeat steps from 1 to 2 and check that worker node is now able to process all messages