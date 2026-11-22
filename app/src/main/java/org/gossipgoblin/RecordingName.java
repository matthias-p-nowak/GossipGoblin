package org.gossipgoblin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Derives the directory and exact six-digit filename from one start instant. */
final class RecordingName {
    /** MediaStore-relative directory and leaf filename. */
    final String directory, filename;

    RecordingName(Instant start, ZoneId zone) {
        // A shared time snapshot prevents mismatched paths at midnight.
        ZonedDateTime local = start.atZone(zone);
        directory = "Recordings/Gossip/" + local.format(DateTimeFormatter.ofPattern("yyyy/MM/dd/", Locale.ROOT));
        filename = "rec-" + local.format(DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT)) + ".m4a";
    }
}
