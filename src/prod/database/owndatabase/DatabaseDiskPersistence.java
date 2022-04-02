package database.owndatabase;

import primary.dataEntities.TestThing;
import utils.ActionQueue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.regex.Pattern;

import static utils.FileUtils.writeString;
import static utils.Invariants.mustBeTrue;
import static utils.StringUtils.decode;

public class DatabaseDiskPersistence {

    static final String databaseFileSuffix = ".db";

    public static final Pattern serializedStringRegex = Pattern.compile("^\\{ ((.*?): (.*?))? }$");
    private final String dbDirectory;
    private final ExecutorService executorService;
    private ActionQueue actionQueue;

    public DatabaseDiskPersistence(String dbDirectory, ExecutorService executorService) {
        this.dbDirectory = dbDirectory;
        this.executorService = executorService;
        actionQueue = new ActionQueue("DatabaseWriter", executorService);
    }

    /**
     * Includes the version
     */
    private String dbDirectoryWithVersion = null;

    /**
     * This function will stop the database persistence cleanly.
     *
     * In order to do this, we need to wait for our threads
     * to finish their work.  In particular, we
     * have offloaded our file writes to [actionQueue], which
     * has an internal thread for serializing all actions
     * on our database
     */
    public void stop() {
        actionQueue.stop();
    }


    /**
     * takes any serializable data and writes it to disk
     *
     * @param item the data we are serializing and writing
     * @param name the name of the data
     */
    <T extends IndexableSerializable<?>> void persistToDisk(T item,String name) {
        final var parentDirectory = "%s%s".formatted(dbDirectoryWithVersion, name);
        actionQueue.enqueue(() -> new File(parentDirectory).mkdirs());

        final var fullPath = "%s/%s%s".formatted(parentDirectory, item.getIndex(), databaseFileSuffix);

        actionQueue.enqueue(() -> writeString(fullPath, item.serialize()));
    }

    /**
     * Deletes a piece of data from the disk
     *
     * Our data consists of directories as containers and each
     * individual piece of data (e.g. [TimeEntry], [Project], etc.) as
     * a file in that directory.  This method simply finds the proper
     * file and deletes it.
     *
     * @param item the data we are serializing and writing
     * @param subDirectory the name of the data, for finding the directory
     */
    public <T extends IndexableSerializable<?>> void deleteOnDisk(T item, String subDirectory) {
        final var fullPath = "%s%s/%s%s".formatted(dbDirectoryWithVersion, subDirectory, item.getIndex(), databaseFileSuffix);
        actionQueue.enqueue(() -> new File(fullPath).delete());
        }


    public <T extends IndexableSerializable<?>> void updateOnDisk(T item, String subDirectory) {
        final var fullPath = "%s%s/%s%s".formatted(dbDirectoryWithVersion, subDirectory, item.getIndex(), databaseFileSuffix);
        final var file = new File(fullPath);

        actionQueue.enqueue(() -> {
            // if the file isn't already there, throw an exception
            mustBeTrue(file.exists(), "we were asked to update %s but it doesn't exist".formatted(file));
            writeString(fullPath, item.serialize());
        });
    }


    public static <T extends IndexableSerializable<?>> T deserialize(String serialized, Function<Map<SerializationKeys, String>, T> converter, List<SerializationKeys> serializationKeys) {
        final var matcher = DatabaseDiskPersistence.serializedStringRegex.matcher(serialized);
        mustBeTrue(matcher.matches(), "the saved data (%s) must match the pattern (%s)".formatted(serialized, DatabaseDiskPersistence.serializedStringRegex.pattern()));
        mustBeTrue(matcher.groupCount() % 3 == 0, "Our regular expression returns three values each time.  The whole match, then the key, then the value.  Thus a multiple of 3");
        var currentIndex = 0;
        final var myMap = new HashMap<SerializationKeys, String>();
        while(true) {
            if (matcher.groupCount() - currentIndex >= 3) {
                int finalCurrentIndex = currentIndex;
                final var keys = serializationKeys.stream().filter(x -> x.getKeyString().equals(matcher.group(finalCurrentIndex + 2))).toList();
                mustBeTrue(keys.size() == 1, "There should only be one key found");
                myMap.put(keys.get(0), decode(matcher.group(currentIndex + 3)));
                currentIndex += 3;
            } else {
                break;
            }
        }
        return converter.apply(myMap);
    }

}
