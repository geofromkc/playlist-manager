package com.apps.geo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.mp3.Mp3Parser;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

public class PlaylistBuilder {

    static String prefix = "";
    static String fileSplit = "";
    static String REGEXAll = ".*([.]mp3|[.]wma)$";
    static String REGEXMP3 = ".*([.]mp3)$";
    static String REGEXM3U = ".*([.]m3u)$";
    static String REGEXCUSTOM = ".*( [(].* UTC[)]).*$";
    static String newDuration = "";

    static String propValue = "";
    static String progressBar = "";

    static String[] mp3FileSplit = null;

    static int mp3PathLen = 0;
    static int playlistPathLen = 0;
    static long fileTotal = 0;
    static long fileProgress = 0;
    static int progressBarStep = 20;
    static long timeStart = 0;

    static ArrayList<String> artistListing = new ArrayList<>();
    static ArrayList<String> genreListing = new ArrayList<>();
    static ArrayList<String> folderListing = new ArrayList<>();
    static ArrayList<String> timeLog = new ArrayList<>();
    static ArrayList<String> fileErrors = new ArrayList<>();

    static Pattern pAll = Pattern.compile(REGEXAll);
    static Pattern pMP3 = Pattern.compile(REGEXMP3);
    static Pattern pM3U = Pattern.compile(REGEXM3U);
    static Pattern pCustom = Pattern.compile(REGEXCUSTOM);
    static Matcher m = null;
    static HashMap<String, ArrayList<String>> artistMap = new HashMap<>();
    static HashMap<String, ArrayList<String>> genreMap = new HashMap<>();
    static HashMap<String, ArrayList<String>> folderMap = new HashMap<>();

    static ContentHandler handler = new DefaultHandler();
    static Metadata meta = new Metadata();
    static Mp3Parser parser = new Mp3Parser();
    static ParseContext parseCtx = new ParseContext();
    static InputStream input = null;

    static ExecutorService executor;
    static {
        executor = Executors.newFixedThreadPool(10);
    }

    static final Object lock = new Object();

    static boolean debug = true;
    static boolean onlyFiles = false;

    /**
     * Initialize the parser object for a media file.  This is the expensive step for retrieving media properties,
     * so do this once for each file.
     * @param fileName Path to media file
     * @return boolean indicating success in creating the parser object
     */
    public static boolean initParser(String fileName)
    {
        StringBuilder timeBar = new StringBuilder();

        long startSeconds = System.nanoTime();
        long totalSeconds;

        long barCount;

        boolean returnCode = false;

        try {
            input = new FileInputStream(fileName);
            parser.parse(input, handler, meta, parseCtx);

            if (debug)
            {
                totalSeconds = (System.nanoTime() - startSeconds) / 1000000;
                barCount = totalSeconds / 10;

                for (int barAdd = 0; barAdd < barCount; barAdd++)
                {
                    timeBar.append("#");
                }
                if (debug)
                    timeLog.add(String.format("\t\t%4s ms in PlaylistBuilder.initParser() %s %n", totalSeconds, timeBar));
            }

            input.close();
            returnCode = true;
        } catch (SAXException | IOException | TikaException e) {
            e.printStackTrace();
        }

        return returnCode;
    }

    public static void printLog() {

        File outputFile;
        outputFile = PlaylistDriver.getFilePath("\nSelect path to the log file", "Debug Log", "txt", onlyFiles);
        if (outputFile == null)
            return;

        BufferedWriter bw = PlaylistDriver.createWriter(outputFile, true);
        if (bw == null)
            System.exit(8);

        timeLog.forEach(entry -> {
            try
            {
                bw.write(entry);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        });

        try
        {
            bw.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

    }

    /**
     *
     * @param fileName Path to the current media file
     * @param propName Property to be extracted
     */
    public static String fileProps(String fileName, String propName)
    {
        if (debug)
            timeLog.add(String.format("\t>> PlaylistBuilder.fileProps(%s, %s): thread '%s'%n", fileName, propName, Thread.currentThread()));

        long totalSeconds;

        try
        {
            if (propName.equals("xmpDM:duration"))
            {
                newDuration = meta.get("xmpDM:duration").split("[.]")[0];
                totalSeconds = (Long.parseLong(newDuration) / 1000);
                propValue = Long.toString(totalSeconds);
            }
            else
            {
                propValue = meta.get(propName);
            }
        }
        catch (Exception e)
        {
            if (debug)
                timeLog.add(String.format("\t   PlaylistBuilder.fileProps(%s, %s): exception %s%n", fileName, propName, e.getMessage()));
        }

        if (debug)
            timeLog.add(String.format("\t<< PlaylistBuilder.fileProps(%s, %s): return '%s'%n", fileName, propName, propValue));
        return propValue;
    }

    /**
     * Create the new playlist file.
     * @param categoryArray An array of category (artist | genre) values, used as keys into the hash of collected files
     * @param bufferedWriter Write object connected to the new output file
     */
    public static boolean fileWriter(ArrayList<String> categoryArray, HashMap<String, ArrayList<String>> categoryMap, BufferedWriter bufferedWriter, String playlistFile) {
        boolean result = true;
        boolean finished = false;
        boolean returnCode = false;

        StringBuilder filePath;
        String mp3FileDuration;
        String mp3FileTitle = "";
        String playlistEntry;
        File fileObj;

        if (debug)
            timeLog.add(String.format("\t%s: PlaylistBuilder.fileWriter().begin%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS)));

        /*
         * Prefix is either "." (if the music root and playlist home path are =) or ".." (if they're not).
         * Condition #1:
         * 		Music root = /device/home
         * 		Playlist home = /device/home
         * Condition #2:
         * 		Music root = /device/home/music
         * 		Playlist home = /device/home/playlists
         */
        int pathStart = (mp3PathLen - prefix.length()) + 1;

        /*
         * Loop through selected categories
         */
        System.out.printf("Added to playlist %s: %n", playlistFile);
        ArrayList<String> fileList;
        for (String entry : categoryArray) {
            /*
             * Each category value is a key into the hash map.  Returned value is array of file paths: "<mp3 duration>,<file path>"
             */
            fileList = categoryMap.get(entry);

            System.out.printf("\t%s (%d)%n", entry, fileList.size());
            for (String fileEntry : fileList) {
                int findComma = fileEntry.indexOf(",");

                mp3FileDuration = fileEntry.substring(0, findComma++);
                fileObj = new File(fileEntry.substring(findComma));

                /*
                 * Extract the file name and remove the file extension
                 */
                try {
                    mp3FileTitle = fileObj.getName().substring(0, (fileObj.getName().length() - 4));
                } catch (Exception e) {
                    System.out.printf("Error file includes a comma in its name, please rename it: '%s' -> '%s'%n", fileEntry, fileObj.getName());
                }

                filePath = new StringBuilder(prefix);

                /*
                 * Tokenize the path to the file
                 */
                try {
                    mp3FileSplit = fileObj.getCanonicalPath().split(fileSplit);
                } catch (IOException e) {
                    System.out.println("Failed to tokenize playlist file path: " + fileEntry.split(",")[1]);
                    e.printStackTrace();
                    result = returnCode;
                    finished = true;
                    break;
                }

                /*
                 * Rebuild the full path starting with the relative path prefix
                 */
                for (int x = pathStart; x < mp3FileSplit.length; x++) {
                    filePath.append(File.separator).append(mp3FileSplit[x]);
                }

                /*
                 * Create the playlist string and write to the file
                 */
                playlistEntry = String.format("%s%n#EXTINF:%s,%s%n%s", System.getProperty("line.separator"), mp3FileDuration, mp3FileTitle, filePath.toString().replaceAll(fileSplit, "/"));

                try {
                    bufferedWriter.write(playlistEntry);
                } catch (IOException e) {
                    System.out.println("Cannot write to playlist file: " + fileEntry.split(",")[1]);
                    e.printStackTrace();
                    result = false;
                    finished = true;
                    break;
                }
            }
            if (finished) break;
        }
        if (!finished) {
            if (debug)
                timeLog.add(String.format("\t%s: PlaylistBuilder.fileWriter().end%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS)));

        }
        return result;
    } // end of fileWriter()

    /**
     * Special routine for genre property.   If available, add it to a hashmap that connects genre names to
     * the associated media files
     * @param fileName Path to the current media file
     * @param mp3Genre String containing the genre value
     * @param mp3Duration String containing the duration of the file in seconds
     */
    public static void genreSave(String fileName, String mp3Genre, String mp3Duration)
    {
        if (debug)
            timeLog.add(String.format(">> %s: PlaylistBuilder.genreSave(%s, %s, %s): thread = '%s'%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Genre, mp3Duration, Thread.currentThread()));

        if (mp3Genre == null || mp3Genre.isEmpty())
        {
            fileErrors.add(String.format("%s has no genre property", fileName));
            if (debug)
                timeLog.add(String.format("<< %s: PlaylistBuilder.genreSave(%s, %s, %s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Genre, mp3Duration));

            return;
        }

        /*
         * Store the duration with the file path, so we don't have to make another
         * call to extract it later
         */
        String outputLine = String.format("%s,%s", mp3Duration, fileName);

        /*
         * Save the file under the specific genre property of the file.
         * Genre property could be missing, print an error msg if so.
         * Note, Only one genre property value is processed.
         */
        if (genreMap.containsKey(mp3Genre))
        {
            genreMap.get(mp3Genre).add(outputLine);
            if (debug)
                timeLog.add(String.format("   %s: PlaylistBuilder.genreSave(%s, %s, %s): update, %s includes %s%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Genre, mp3Duration, mp3Genre, outputLine));
        }
        else
        {
            ArrayList<String> fileList = new ArrayList<>();

            fileList.add(outputLine);
            genreMap.put(mp3Genre, fileList);
            genreListing.add(mp3Genre);

            if (debug)
                timeLog.add(String.format("   %s: PlaylistBuilder.genreSave(%s, %s, %s): new, %s = %s%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Genre, mp3Duration, mp3Genre, outputLine));
        }

        if (debug)
            timeLog.add(String.format("<< %s: PlaylistBuilder.genreSave(%s, %s, %s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Genre, mp3Duration));
    }

    /**
     * Special routine for artist property.   If available, add it to a hashmap that connects artist names to
     * the associated media files
     * @param fileName Path to the current media file
     * @param mp3Artist String containing the artist name
     * @param mp3Duration String containing the duration of the file in seconds
     */
    public static void artistSave(String fileName, String mp3Artist, String mp3Duration, boolean flagEmpty)
    {
        if (debug)
            timeLog.add(String.format(">> %s: PlaylistBuilder.artistSave(%s, %s, %s): thread = '%s'%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Artist, mp3Duration, Thread.currentThread()));

        if (mp3Artist == null || mp3Artist.isEmpty())
        {
            if (flagEmpty)
                fileErrors.add(String.format("%s has no artist property", fileName));

            if (debug)
                timeLog.add(String.format("<< %s: PlaylistBuilder.artistSave(%s, %s, %s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Artist, mp3Duration));

            return;
        }

        /*
         * Store the duration with the file path, so we don't have to make another
         * call to extract it later
         */
        String outputLine = String.format("%s,%s", mp3Duration, fileName);


        /*
         * Save it under the specific artist property of the file.
         * Artist property could be missing, print an error msg if so.
         */
        if (artistMap.containsKey(mp3Artist))
        {
            artistMap.get(mp3Artist).add(outputLine);
        }
        else
        {
            ArrayList<String> fileList = new ArrayList<>();

            fileList.add(outputLine);
            artistMap.put(mp3Artist, fileList);
            artistListing.add(mp3Artist);
        }

        if (debug)
            timeLog.add(String.format("<< %s: PlaylistBuilder.artistSave(%s, %s, %s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, mp3Artist, mp3Duration));
    }

    /**
     * Special routine for the parent folder of the media file.
     * This is a somewhat special case, for music folders that contain multiple artists and genres
     * 		that don't warrant their own separate artist folders
     * @param fileName Path to the current media file
     * @param parentFolder String containing the containing folder
     * @param mp3Duration String containing the duration of the file in seconds
     */
    public static void folderSave(String fileName, String parentFolder, String mp3Duration)
    {
        if (debug)
            timeLog.add(String.format(">> %s: PlaylistBuilder.folderSave(%s, %s, %s): thread = '%s'%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, parentFolder, mp3Duration, Thread.currentThread()));

        /*
         * Store the duration with the file path, so we don't have to make another
         * call to extract it later
         */
        String outputLine = String.format("%s,%s", mp3Duration, fileName);

        /*
         * Save it under the specific parent folder of the file.
         */
        if (folderMap.containsKey(parentFolder))
        {
            folderMap.get(parentFolder).add(outputLine);
        }
        else
        {
            ArrayList<String> fileList = new ArrayList<>();

            fileList.add(outputLine);
            folderMap.put(parentFolder, fileList);
            folderListing.add(parentFolder);
        }

        if (debug)
            timeLog.add(String.format("<< %s: PlaylistBuilder.folderSave(%s, %s, %s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName, parentFolder, mp3Duration));
    }

    public static long fileCount(String fileName)
    {
        if (debug)
            timeLog.add(String.format(">> %s: PlaylistBuilder.fileCount(%s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName));

        long fileNumber = 0;

        File dirTest = new File(fileName);
        if (!dirTest.isDirectory())
            return 0;

        try (Stream<Path> walk = Files.walk(Paths.get(fileName))) {

            List<String> fileNamesList = walk.map(Path::toString)
                    .filter(f -> f.contains(".mp3")||f.contains(".wma")||f.contains(".MP3")||f.contains(".WMA"))
                    .toList();

            fileNumber = fileNamesList.size();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (debug)
            timeLog.add(String.format("<< %s: PlaylistBuilder.fileCount(%s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileName));
        return fileNumber;
    }

    private static class SaveAudioProperties2 implements Runnable
    {
        String filePath;

        public SaveAudioProperties2(String pathArg)
        {
            filePath = pathArg;
        }

        @Override
        public void run() {

            if (filePath.toLowerCase().contains(".wma"))
            {
                fileErrors.add(String.format("%s is in an incompatible format, convert to MP3 format for processing", filePath));
                return;
            }

            Instant start = Instant.now();
            LocalTime startTime = java.time.LocalTime.now();
            if (debug)
                timeLog.add(String.format(">> %s: PlaylistBuilder.SaveAudioProperties2.run(%s): thread '%s'%n", startTime.truncatedTo(ChronoUnit.MILLIS), filePath, Thread.currentThread()));

            String mp3Duration;
            String mp3Genre;
            String mp3Artist;
            String mp3AlbumArtist;

            Metadata meta = new Metadata();
            Mp3Parser parser = new Mp3Parser();
            ParseContext parseCtx = new ParseContext();
            InputStream input;

            try {
                input = new FileInputStream(filePath);
                parser.parse(input, handler, meta, parseCtx);

                input.close();
            }
            catch (IOException | TikaException | SAXException e)
            {
                e.printStackTrace();
            }

            String newDuration = meta.get("xmpDM:duration").split("[.]")[0];
            mp3Duration = Long.toString((Long.parseLong(newDuration) / 1000));
            mp3Genre = meta.get("xmpDM:genre");
            mp3Artist = meta.get("xmpDM:artist");
            mp3AlbumArtist = meta.get("xmpDM:albumArtist");

            File dirTest = new File(filePath);
            synchronized(lock)
            {
                genreSave(filePath, mp3Genre, mp3Duration);
                artistSave(filePath, mp3Artist, mp3Duration, true);
                artistSave(filePath, mp3AlbumArtist, mp3Duration, false);
                folderSave(filePath, dirTest.getParent(), mp3Duration);

                ++fileProgress;
                if (fileProgress%progressBarStep == 0)
                    System.out.printf("\r%s: (%s/%d)", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), fileProgress, fileTotal);

                Instant finish = Instant.now();
                long timeElapsed = Duration.between(start, finish).toMillis();

                LocalTime doneTime = java.time.LocalTime.now();
                if (debug)
                    timeLog.add(String.format("<< %s: PlaylistBuilder.SaveAudioProperties2.run(%s): (%d/%d) thread '%s' finished in %.3f sec%n", doneTime.truncatedTo(ChronoUnit.MILLIS), filePath, fileProgress, fileTotal, Thread.currentThread(), (timeElapsed / 1000.0)));
            }
        }
    }

    public static void walkFileTreeCall(String treeStart)
    {
        if (debug)
            timeLog.add(String.format(">> %s: PlaylistBuilder.walkFileTreeCall(%s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), treeStart));

        List<Callable<Object>> threadSet = new ArrayList<>((int) PlaylistBuilder.fileTotal);

        System.out.printf("%s: Starting%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS));

        try (Stream<Path> walk = Files.walk(Paths.get(treeStart)))
        {
            walk.map(Path::toString)
                    .filter(f -> f.contains(".mp3")||f.contains(".wma")||f.contains(".MP3")||f.contains(".WMA"))
                    .forEach(filePath -> threadSet.add(Executors.callable(new SaveAudioProperties2(filePath))));

            @SuppressWarnings("unused")
            List<Future<Object>> answers = executor.invokeAll(threadSet);
            executor.shutdown();
        }
        catch (IOException | InterruptedException e)
        {
            e.printStackTrace();
        }

		/*
		 *
		if (debug) timeLog.add(String.format("   %s: PlaylistBuilder.walkFileTreeCall(%s): show collected genres%n", java.time.LocalTime.now(), treeStart));

		List<String> genres = new ArrayList<String>();
		for (String key: genreMap.keySet())
		{
			if (debug) timeLog.add(String.format("   \t%s: PlaylistBuilder.walkFileTreeCall(%s): genre map key '%s'%n", java.time.LocalTime.now(), treeStart, key));

			genres = genreMap.get(key);
			genres
				.stream()
				.forEach(savedGenre -> timeLog.add(String.format("   \t%s: PlaylistBuilder.walkFileTreeCall(%s): %s%n", java.time.LocalTime.now(), treeStart, savedGenre)));
		}

		if (debug) timeLog.add(String.format("   %s: PlaylistBuilder.walkFileTreeCall(%s): end of collected genres%n", java.time.LocalTime.now(), treeStart));
		 */

        /*
         * try (Stream<Path> walk = Files.walk(Paths.get(treeStart))) {
         *
         * walk.map(x -> x.toString()) .filter(f ->
         * f.contains(".mp3")||f.contains(".wma")) .forEach(y -> executor.submit(new
         * Callable<Object>() {
         *
         * @Override public Object call() { getFileProperties(y); return null; } })); }
         * catch (IOException e) { e.printStackTrace(); }
         *
         * try (Stream<Path> walk = Files.walk(Paths.get(treeStart))) {
         *
         * walk.map(x -> x.toString()) .filter(f ->
         * f.contains(".mp3")||f.contains(".wma")) .forEach(y -> executor.submit(new
         * Callable<Object>() {
         *
         * @Override public Object call() { getFileProperties(y); return null; } })); }
         * catch (IOException e) { e.printStackTrace(); }
         */

        if (debug) {
            timeLog.add(String.format("<< %s: PlaylistBuilder.walkFileTreeCall(%s)%n", java.time.LocalTime.now().truncatedTo(ChronoUnit.MILLIS), treeStart));
        } else {
            return;
        }
    }

    /**
     * Validate the relationship between the media files' path and the new playlist file path.
     * Set the prefix (current vs parent) based on the path length;
     */
    public static void setPathLengths(File mediaRoot, File playlistRoot)
    {
        try
        {
            /*
             * Calculate the path lengths to know if output paths can be correctly constructed
             */
            mp3PathLen = mediaRoot.getCanonicalPath().split(fileSplit).length;
            playlistPathLen = playlistRoot.getParent().split(fileSplit).length;

            /*
             * If the path to the music folders and the path to the playlist file are the same,
             * adjust the path prefix
             */
            if (mediaRoot.getCanonicalPath().equals(playlistRoot.getParent()))
                prefix = ".";
            else
                prefix = "..";
        }
        catch (IOException e1)
        {
            e1.printStackTrace();
            System.exit(0);
        }
    }

    public static void showMP3Errors()
    {
        System.out.println("\n");

        for (String fe: fileErrors)
        {
            System.out.println(fe);
        }
    }

    /*
     * For a specific media file, show the property values for:
     * 		Duration
     * 		Genre
     * 		Contributing Artist
     */
    public static void showProps(Scanner keyboard)
    {
        System.out.print("Enter path to target file: ");
        String fileName = keyboard.nextLine();

        System.out.printf("Target file = '%s'%n", fileName);

        initParser(fileName);

        System.out.println("Possible property values:");
        for (String metadataName: meta.names())
            System.out.printf("\t%s%n", metadataName);

        String mp3Duration = fileProps(fileName, "xmpDM:duration");
        String mp3Genre = fileProps(fileName, "xmpDM:genre");
        String mp3Artist = fileProps(fileName, "xmpDM:artist");
        String mp3AlbumArtist = fileProps(fileName, "xmpDM:albumArtist");

        System.out.println("mp3Duration = '" + mp3Duration + "'");
        System.out.println("mp3Genre = '" + mp3Genre + "'");
        System.out.println("mp3Artist = '" + mp3Artist + "'");
        System.out.println("mp3AlbumArtist = '" + mp3AlbumArtist + "'");

    } // end of showProps(Scanner keyboard)

    /**
     * Display current playlist files.  The current directory path needs be checked in order
     * to verify the location of the playlist files.
     * @param pathRoot String path root of the music files
     * @param dirListing String directory listing of the path root
     * @param songDetail boolean flag to optionally print all songs for each artist
     */
    public static void showPlayLists(String pathRoot, String[] dirListing, boolean songDetail)
    {
        int tokenLength;
        String artistName;
        String line;
        String[] playlistEntry;
        String[] playlistRoot;
        String entryPath;

        HashMap<String, List<String>> artistsInPlaylists = new HashMap<>(200);
        BufferedReader br;
        File dirObject;

        if (dirListing == null)
            return;

        /* Set the default directory listing.
         * This assumes the following structure:
         * 		/root/
         * 			ArtistOne/
         * 				SongOne.mp3
         * 			ArtistTwo/
         * 				SongTwo.mp3
         * 			PlaylistOnw.m3u
         * 			PlaylistTwo.m3u
         */
        playlistRoot = dirListing;

        /*
         * Use the first directory entry as the starting point.
         * Then go to its parent for inspection.
         */
        entryPath = pathRoot + File.separator + dirListing[0];
        dirObject = new File(entryPath);

        entryPath = dirObject.getParent();
        dirObject = new File(entryPath);

        /*
         * If the parent directory is "Music" then the expected structure is:
         * 		/root/
         * 			Music/
         * 				ArtistOne/
         * 					SongOne.mp3
         * 				ArtistTwo/
         * 					SongTwo.mp3
         * 			Playlists/
         * 				PlaylistOne.m3u
         * 				PlaylistTwo.m3u
         */
        if (dirObject.getName().equalsIgnoreCase("Music"))
        {
            entryPath = dirObject.getParent() + File.separator + "Playlists";
            dirObject = new File(entryPath);
            pathRoot = entryPath;
            playlistRoot = dirObject.list();
        }

        assert playlistRoot != null;
        for (String dirEntry: playlistRoot)
        {
            entryPath = pathRoot + File.separator + dirEntry;
            dirObject = new File(entryPath);

            if (!dirObject.isDirectory())
            {

                m = pM3U.matcher(dirObject.getName());

                if (m.matches())
                {
                    System.out.printf("%nPlaylist: %s %n", dirObject.getName());
                    artistName = "";

                    try {
                        br = new BufferedReader(new FileReader(dirObject.getCanonicalPath()));
                        while ((line = br.readLine()) != null)
                        {
                            m = pMP3.matcher(line);
                            if (m.matches())
                            {
                                line = line.replace("/", "\\");

                                playlistEntry = line.split(File.separator + File.separator);
                                tokenLength = playlistEntry.length;

                                if (!artistName.equals(playlistEntry[tokenLength-2]))
                                {
                                    artistName = playlistEntry[tokenLength-2];
                                    System.out.printf("\tArtist: %s %n", artistName);

                                    if (!artistsInPlaylists.containsKey(artistName))
                                    {
                                        List<String> tempList = new ArrayList<>();
                                        tempList.add(dirObject.getName());
                                        artistsInPlaylists.put(artistName, tempList);
                                    }
                                    else
                                    {
                                        List<String> tempList = artistsInPlaylists.get(artistName);
                                        if (!tempList.contains(dirObject.getName()))
                                        {
                                            tempList.add(dirObject.getName());
                                            artistsInPlaylists.put(artistName, tempList);
                                        }
                                    }
                                }
                                if (songDetail)
                                    System.out.printf("\t\t%s %n", playlistEntry[tokenLength-1]);
                            }
                        }

                        br.close();
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                        return;
                    }
                }
            }

        }

        for (String artist: artistsInPlaylists.keySet())
        {
            System.out.printf("%n%s", artist);
            List<String> tempList = artistsInPlaylists.get(artist);
            tempList.forEach(playlist -> System.out.printf("%n\t%s", playlist));
        }
    }
}
