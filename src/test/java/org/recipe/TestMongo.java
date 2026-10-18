package org.recipe;

import org.bson.Document;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.process.io.ProcessOutput;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.transitions.Start;

/**
 * A real MongoDB for the store tests, started once (on a free port) and
 * stopped when the test JVM exits. The first run downloads MongoDB into
 * ~/.embedmongo.
 */
public final class TestMongo {

    private static TransitionWalker.ReachedState<RunningMongodProcess> server;
    private static MongoClient client;

    private TestMongo() {
    }

    public static synchronized MongoClient client() {
        if (client == null) {
            server = Mongod.instance()
                    .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()))
                    .start(Version.Main.V7_0);
            client = MongoClients.create("mongodb://" + server.current().getServerAddress());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                client.close();
                server.close();
            }));
        }
        return client;
    }

    // An empty collection in the test database
    public static MongoCollection<Document> collection(String name) {
        MongoCollection<Document> collection = client().getDatabase("recipetool-test").getCollection(name);
        collection.drop();
        return collection;
    }

    // A collection on a server that isn't there, which fails within a moment
    public static MongoCollection<Document> unreachable(String name) {
        return MongoClients.create("mongodb://localhost:1/?serverSelectionTimeoutMS=200&connectTimeoutMS=200")
                .getDatabase("recipetool-test").getCollection(name);
    }
}
