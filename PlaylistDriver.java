package com.apps.geo;

/**
 * Application to create M3U playlist files for media systems.  For example, various mp3 players
 * and some auto entertainment systems support the m3u format.
 *
 * #EXTM3U
 * #EXTINF:314,Infinity Machine
 * ./Passport/Infinity Machine.mp3
 *
 *  Typical run:
 *      Generating internal list of media files
 *      22:10:32.449: Starting
 *      22:12:02.430: (3060/3075)
 *      Select path to the playlist file
 *      User selected playlist file C:\Users\geo\Documents\Music\TestList.m3u
 *      Playlist file already exists. Append to current file?  (Y/N)> y
 *      Using playlist file C:\Users\geo\Documents\Music\TestList.m3u
 *      Select playlist type:
 *          1. Master
 *          2. Genre-based
 *          3. Artist-based
 *          4. Folder-based
 *          99. Exit
 *
 * @author geo
 */

import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import javax.swing.JFileChooser;
//import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;

public class PlaylistDriver {
    static boolean debug = true;
    static long timeStart = 0;

    static final String selectAll = "1";
    static final String selectGenre = "2";
    static final String selectArtist = "3";
    static final String selectFolder = "4";
    static final String selectExit = "99";

    static final String argHelp = "-h";
    //static final String argDebug = "-d";
    static final String argSaveGenres = "-g";
    static final String argSaveArtists = "-a";
    static final String argShowProps = "-p";
    static final String argShowPlaylistSummary = "-ys";
    static final String argShowPlaylistDetail = "-yd";
    static boolean onlyFolders = true;
    static boolean onlyFiles = false;
    static File cancelled = null;

    /**
     *
     * @param args Optional command line arguments
     */
    public static void main(String[] args) {

        Scanner keyboard;
        BufferedWriter bufferedWriter;
        ArrayList<String> selectedCategory = new ArrayList<>();

        String fileSplit;
        String playlistType;
        String[] fileListing;

        boolean saveGenreMap = false;
        boolean saveArtistMap = false;
        boolean showFileProps = false;
        boolean showPlayListSummary = false;
        boolean showPlayListDetail = false;
        boolean fileNew = true;

        /*
         * String operations on file paths depends on OS.   Windows requires a modified version of
         * the OS File.separator for certain String regex operations.
         */
        fileSplit = File.separator;
        if (System.getProperty("os.name").contains("Windows"))
        {
            fileSplit += File.separator;
        }

        // Make this available to the builder class methods
        PlaylistBuilder.fileSplit = fileSplit;

        /*
         * Check to see if Genre, Artist or Folder inventory mapping file is requested
         */
        for (String runtimeArg : args) {
            switch (runtimeArg) {
                case argHelp -> showHelp();
                case argSaveGenres -> saveGenreMap = true;
                case argSaveArtists -> saveArtistMap = true;
                case argShowProps -> showFileProps = true;
                case argShowPlaylistSummary -> showPlayListSummary = true;
                case argShowPlaylistDetail -> showPlayListDetail = true;
                default -> { }
            }
            debug = true;
            PlaylistBuilder.debug = true;
        }

        keyboard = new Scanner(System.in);

        /*
         * Single-task option to show relevant audio properties for a specific file
         */
        if (showFileProps)
        {
            PlaylistBuilder.showProps(keyboard);
            System.exit(0);
        }

        /*
         * ****************** Begin user interactions ********************************
         */
        File headDirectory = getFilePath("Select top-level media folder", "File Directory", "txt", onlyFolders);

        if ((headDirectory == null) || (headDirectory.list() == null))
        {
            System.out.printf("\n* * Failed to list media files%n");
            System.exit(0);
        }

        fileListing = headDirectory.list();

        /*
         * Single-task options to show summary contents of existing playlists
         */
        if (showPlayListSummary || showPlayListDetail)
        {
            PlaylistBuilder.showPlayLists(headDirectory.getPath(), fileListing, showPlayListDetail);
            System.exit(0);
        }

        /*
         * Create a list of files in memory for subsequent processing
         */
        timeStart = System.nanoTime();

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main().begin %n", java.time.LocalTime.now()));

        System.out.print("\nGenerating internal list of media files\n");

        long counter = PlaylistBuilder.fileCount(headDirectory.getPath());
        PlaylistBuilder.fileTotal = counter;

        /*
         * Starting at the provided root directory, process each entry in the directory tree and extract audio properties
         */
        PlaylistBuilder.walkFileTreeCall(headDirectory.getPath());
        PlaylistBuilder.timeLog.add(String.format("\rAudio file count: %s%n", counter));

        if (debug)
            PlaylistBuilder.timeLog.add(String.format(">> %s PlaylistDriver.main().media files stored in %4s ms %n", java.time.LocalTime.now(), (System.nanoTime() - timeStart) / 1000000));

        /*
         * Write collected genre and/or artist values to a file if requested
         */
        if (saveGenreMap)
        {
            File genreFile = getFilePath("\nSelect path to the genre inventory file", "Text file", "txt", onlyFiles);
            writeCategoryFile(genreFile.getPath(), PlaylistBuilder.genreMap, true);
        }

        if (saveArtistMap)
        {
            File artistFile = getFilePath("\nSelect path to the artist inventory file", "Text file", "txt", onlyFiles);
            writeCategoryFile(artistFile.getPath(), PlaylistBuilder.artistMap, false);
        }

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main().Done writing genre/artist files %n", java.time.LocalTime.now()));

        /*
         * Main user interaction loop.
         * Exit loop when user either cancels the prompt for a playlist file, or responds positively to a prompt for completion
         */
        boolean sessionStop;
        do
        {
            if (debug)
                PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main().process get playlist file path %n", java.time.LocalTime.now()));

            /*
             * Retrieve path to the playlist file
             */
            File playlistFile = getNewPlaylistFile(headDirectory);
            if (playlistFile == null)
                System.exit(0);

            if (playlistFile.exists())
            {
                System.out.println("Playlist file already exists. Append to current file?  (Y/N)> ");
                String playlistAppend = keyboard.nextLine();

                if (playlistAppend.equalsIgnoreCase("Y"))
                    fileNew = false;
            }
            System.out.printf("Using playlist file %s%n", playlistFile);

            playlistType = getPlaylistType(keyboard);

            if (debug)
                PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main(): get selected categories from user %n", java.time.LocalTime.now()));

            /*
             * Check playlist type, collect selected categories from user
             * Selecting 'all' will internally collect all genres to generate a master list
             */
            switch (playlistType) {
                case selectAll -> {
                    /*
                     * If all songs are requested for the playlist, use the keys in the genre mapping HashMap
                     * to create a master list of genre values.   This will be used to create the master playlist.
                     */
                    String[] keys = PlaylistBuilder.genreMap
                            .keySet()
                            .toArray(new String[PlaylistBuilder.genreListing.size()]);
                    Collections.addAll(selectedCategory, keys);
                }
                case selectGenre -> {
                    Collections.sort(PlaylistBuilder.genreListing);
                    selectedCategory = getCategory(keyboard, PlaylistBuilder.genreListing, playlistFile.getName());
                }
                case selectArtist -> {
                    Collections.sort(PlaylistBuilder.artistListing);
                    selectedCategory = getCategory(keyboard, PlaylistBuilder.artistListing, playlistFile.getName());
                }
                case selectFolder -> {
                    Collections.sort(PlaylistBuilder.folderListing);
                    selectedCategory = getCategory(keyboard, PlaylistBuilder.folderListing, playlistFile.getName());
                }
                default -> { }
            }

            if (debug)
                PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main().process create writer for playlist file %n", java.time.LocalTime.now()));

            /*
             * Create the playlist output file
             */
            bufferedWriter = createWriter(playlistFile, fileNew);
            if (bufferedWriter == null)
                System.exit(8);

            try
            {
                if (fileNew)
                    bufferedWriter.write("#EXTM3U");
            }
            catch (IOException e1)
            {
                e1.printStackTrace();
            }

            if (debug)
                PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main().process write playlist file %n", java.time.LocalTime.now()));
            /*
             * Write the new playlist file
             */
            HashMap<String, ArrayList<String>> newPlaylistMap =
            switch (playlistType) {
                case selectAll, selectGenre -> PlaylistBuilder.genreMap;
                case selectArtist -> PlaylistBuilder.artistMap;
                case selectFolder -> PlaylistBuilder.folderMap;
                default -> throw new IllegalStateException("Unexpected value: " + playlistType);
            };
            if (!PlaylistBuilder.fileWriter(selectedCategory, newPlaylistMap, bufferedWriter, playlistFile.getName()))
                System.out.println("Failed to write playlist file");

            /*
             * Write the new playlist file
             */
            /*
            switch (playlistType) {
                case selectAll, selectGenre -> {
                    if (!PlaylistBuilder.fileWriter(selectedCategory, PlaylistBuilder.genreMap, bufferedWriter, playlistFile.getName())) {
                        System.out.println("Failed to write playlist file");
                    }
                }
                case selectArtist -> {
                    if (!PlaylistBuilder.fileWriter(selectedCategory, PlaylistBuilder.artistMap, bufferedWriter, playlistFile.getName())) {
                        System.out.println("Failed to write playlist file");
                    }
                }
                case selectFolder -> {
                    if (!PlaylistBuilder.fileWriter(selectedCategory, PlaylistBuilder.folderMap, bufferedWriter, playlistFile.getName())) {
                        System.out.println("Failed to write playlist file");
                    }
                }
            }
             */

            if (debug)
                PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.main().process write playlist file %n", java.time.LocalTime.now()));

            /*
             * Close resources.  Closing the BufferedWriter is necessary so the buffer is
             * flushed with the last set of lines.   Otherwise, the output file may be truncated.
             */
            try
            {
                bufferedWriter.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            /*
             * Prompt user for additional playlists
             */
            System.out.print("\nDo you want to create another playlist file? (Y/N): ");
            String sessionPrompt = keyboard.nextLine().toLowerCase();

            sessionStop = sessionPrompt.equals("y");

        } while (sessionStop);

        keyboard.close();

        /*
         * DEBUG: If timings were captured, show the log and any file errors
         */
        PlaylistBuilder.showMP3Errors();

        if (debug)
            PlaylistBuilder.printLog();

    } // end of main()

    /*
        Prompt for location of new playlist file.  If a selection is made, validate its location with respect to the audio files.
     */
    @Nullable
    private static File getNewPlaylistFile(File headDirectory)
    {
        File playlistFile;
        playlistFile = getFilePath("\nSelect path to the playlist file", "Media playlist", "m3u", onlyFiles);

        // If user pressed cancel, nothing to do
        if (userCancel(playlistFile))
            return null;

        System.out.println("Selected playlist file: " + playlistFile.getPath());

        /*
         * Calculate path lengths for the media root and the playlist file
         */
        PlaylistBuilder.setPathLengths(headDirectory, playlistFile);

        if (PlaylistBuilder.mp3PathLen != PlaylistBuilder.playlistPathLen)
        {
            System.out.printf("Path to the playlist file '%s' is invalid, it must have a common directory parent and be at the same node level as the media file root '%s'", playlistFile.getPath(), headDirectory.getPath());
            return null;
        }

        return playlistFile;
    }

    private static boolean userCancel(File playList)
    {
        boolean returnState = false;

        if (playList == cancelled)
        {
            System.out.println("Cancelled...");

            PlaylistBuilder.showMP3Errors();

            if (debug)
                PlaylistBuilder.printLog();

            returnState = true;
        }

        return returnState;
    }

    /**
     * Write the inventory of genres to a text file, as in:
     * 		Category (genre or artist)
     * 			.../Candy Dulfer/Finsbury Park
     * 			.../Candy Dulfer/Freak Out.mp3
     *
     * @param outputCategoryMapPath String path to the output file
     * @param categoryMap HashMap where the category values are keys to arraylists of associated media files
     */
    public static void writeCategoryFile(String outputCategoryMapPath, HashMap<String, ArrayList<String>> categoryMap, boolean onlySummary)
    {
        String currentCategory;
        String mediaParent;
        File currentFile;

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.writeCategoryFile().begin create BufferedWriter for '%s'%n", java.time.LocalTime.now(), outputCategoryMapPath));

        BufferedWriter bufferedWriter = null;

        /*
         * Create and initialize the category mapping output file
         */
        try
        {
            FileWriter writer = new FileWriter(outputCategoryMapPath, false);
            bufferedWriter = new BufferedWriter(writer);
        }
        catch (IOException e)
        {
            System.out.printf("%n* * * Output file path %s is invalid * * *%n", outputCategoryMapPath );
            System.exit(0);
        }

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.writeCategoryFile().process retrieve keys %n", java.time.LocalTime.now()));

        /*
         * Retrieve the category keys from the HashMap and sort them
         */
        String[] keys = categoryMap.keySet().toArray(new String[0]);
        List<String> sortedKeys = Arrays.asList(keys);
        Collections.sort(sortedKeys);

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.writeCategoryFile().process write inventory file %n", java.time.LocalTime.now()));

        /*
         * Loop through the keys.   Each entry value is an ArrayList of file names
         */
        for (String mapKey: sortedKeys)
        {
            try
            {
                bufferedWriter.write(mapKey);
                bufferedWriter.newLine();

                currentCategory = "";
                for (String fileName: categoryMap.get(mapKey))
                {
                    if (onlySummary)
                    {
                        currentFile = new File(fileName);
                        mediaParent = currentFile.getParent();
                        currentFile = new File(mediaParent);

                        if (!currentFile.getName().equals(currentCategory))
                        {
                            currentCategory = currentFile.getName();
                            bufferedWriter.write(String.format("\t%s", currentCategory));
                            bufferedWriter.newLine();
                        }
                    }
                    else
                    {
                        bufferedWriter.write(String.format("\t%s", fileName));
                        bufferedWriter.newLine();
                    }

                }
            }
            catch (IOException e)
            {
                PlaylistBuilder.timeLog.add("PlaylistDriver.writeCategoryFileCategory(): mapping file could not be written to");
                PlaylistBuilder.timeLog.add(e.getMessage());

                System.exit(0);
            }
            catch (NullPointerException en)
            {
                PlaylistBuilder.timeLog.add("PlaylistDriver.writeCategoryFileCategory(): Found null map key");
            }
        }

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.writeCategoryFile().end close bufferedwriter %n", java.time.LocalTime.now()));

        /*
         * Close resources.  Closing the BufferedWriter is necessary so the buffer is
         * flushed with the last set of lines.  Otherwise the output file may be truncated.
         */
        try
        {
            bufferedWriter.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    } // end of writeCategoryFile(String outputCategoryMapPath, HashMap<String, ArrayList<String>> categoryMap)

    /*
     * Prompt user for playlist file path using new jFileChooser window
     */
    public static File getFilePath(String userPrompt, String fileDesc, String fileExt, boolean selectMode)
    {
        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.getFilePath().begin for '%s'%n", java.time.LocalTime.now(), userPrompt.trim()));

        /*
         * Prompt for path to the playlist file
         */
        File outputPath = null;

        try
        {
            JFileChooser fileChooser = new JFileChooser();

            fileChooser.setCurrentDirectory(new File(System.getenv("HOMEPATH")));
            fileChooser.setDialogTitle(userPrompt);
            fileChooser.setFileFilter(new FileNameExtensionFilter(fileDesc, fileExt));

            if (selectMode)
                fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

            if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            {
                outputPath = fileChooser.getSelectedFile();
            }
        }
        catch (HeadlessException e1)
        {
            e1.printStackTrace();
            System.out.println("Error setting path for " + fileDesc);
            outputPath = null;
        }

        if (debug)
            PlaylistBuilder.timeLog.add(String.format("%s: PlaylistDriver.getFilePath().end%n", java.time.LocalTime.now()));

        return outputPath;

    } // end of getFilePath(String userPrompt, String fileDesc, String fileExt)

    /**
     * Prompt user for type of playlist.  Either all are included, or a subset based on selected genre or artist values
     * @param keyboard
     * @return
     */
    public static String getPlaylistType(Scanner keyboard)
    {
        String playlistType = null;

        do
        {
            System.out.println("Select playlist type:");
            System.out.printf("%s. Master%n", selectAll);
            System.out.printf("%s. Genre-based%n", selectGenre);
            System.out.printf("%s. Artist-based%n", selectArtist);
            System.out.printf("%s. Folder-based%n", selectFolder);
            System.out.println("99. Exit");
            System.out.print("> ");

            playlistType = keyboard.nextLine();

            switch (playlistType)
            {
                case selectAll:
                case selectGenre:
                case selectArtist:
                case selectFolder:
                case selectExit:
                    break;
                default:
                    playlistType = "";
            }
        } while (playlistType.equals(""));

        return playlistType;
    } // end of getPlaylistType(Scanner keyboard)

    /**
     * Display four-column list of categories and prompt user for selections
     * @param keyboard Scanner object for user interaction
     * @param categoryList ArrayList of the available categories
     * @return The ArrayList of selected categories
     */
    public static ArrayList<String> getCategory(Scanner keyboard, ArrayList<String> categoryList, String playlistFile)
    {
        ArrayList<String> selectedCategory = new ArrayList<String>();

        String categorySubstring;
        String firstCol = "\n";
        String secondCol = "\t";
        String tabOver = firstCol;
        String[] categories = null;

        int categorySelectIndex = 0;
        int categoryKeyIndex = 0;
        int colNumber = 1;

        /*
         * Prompt user for category values.
         */
        do
        {
            // Start with the exit option
            System.out.printf("%nSelect category(s) for playlist file %s:%n", playlistFile);
            System.out.println("\n  0. Finished with selection(s)");

            /*
             * Print out the categories in a four-column format.
             * Left-justify the odd-numbered options and tab over the even-numbered options.
             */
            categorySelectIndex = 1;
            tabOver = firstCol;
            colNumber = 1;
            String showCategory;

            for (String categorySelection: categoryList)
            {
                if (categorySelection != null)
                {
                    showCategory = (categorySelection.contains(File.separator)) ? new File(categorySelection).getName() : categorySelection;
                    categorySubstring = (showCategory.length() > 26) ? showCategory.substring(0, 23) + "...": showCategory;

                    System.out.printf("%s%3d. %-26s", tabOver, categorySelectIndex, categorySubstring);

                    switch (colNumber) {
                        case 1, 2, 3 -> {
                            tabOver = secondCol;
                            colNumber++;
                        }
                        case 4 -> {
                            tabOver = firstCol;
                            colNumber = 1;
                        }
                    }

                    categorySelectIndex++;
                }
            }

            /*
             * After displaying the options, list any previously selected categories
             */
            if (selectedCategory.size() > 0)
            {
                System.out.print("\n(Already selected:");
                for (String entry: selectedCategory)
                {
                    System.out.printf(" '%s'", entry);
                }
                System.out.print(")");
            }

            /*
             * Prompt for next category
             */
            System.out.print("\n> ");
            categories = keyboard.nextLine().split(" ");
            for (String newCategory: categories)
            {
                try
                {
                    categoryKeyIndex = Integer.parseInt(newCategory.trim());
                }
                catch (NumberFormatException ne)
                {
                    categoryKeyIndex = 999;
                    continue;
                }

                if (categoryKeyIndex == 0)
                    break;

                if (categoryKeyIndex >= categorySelectIndex)
                    continue;

                // Only add category if it hasn't already been selected
                if (!selectedCategory.contains(categoryList.get(categoryKeyIndex - 1)))
                    selectedCategory.add(categoryList.get(categoryKeyIndex - 1));
            }

        } while (categoryKeyIndex > 0);

        return selectedCategory;
    } // end of getCategory(Scanner keyboard, ArrayList<String> categoryList)


    /**
     *
     * @param outputFile
     * @param fileNew
     */
    public static BufferedWriter createWriter(File outputFile, boolean fileNew)
    {
        FileWriter writer;
        BufferedWriter bufferedWriter;

        /*
         * Create and initialize the playlist output file
         */
        try
        {
            if (fileNew)
                writer = new FileWriter(outputFile, false);
            else
                writer = new FileWriter(outputFile, true);

            bufferedWriter = new BufferedWriter(writer);

            // initialize new files
            if (fileNew)
            {
                bufferedWriter.newLine();
            }
        }
        catch (IOException e)
        {
            System.out.printf("%n* * * Output file path %s is invalid * * *%n", outputFile);
            e.printStackTrace();

            bufferedWriter = null;
        }

        return bufferedWriter;
    }

    private static void showHelp()
    {
        System.out.println("\n=============================================\n");
        System.out.println("This application will assist with building playlist files suitable for any system that uses the M3U playlist format.");
        System.out.println("\nThe root of the audio files is provided, and the child directories are scanned to organize all discovered audio files.   ");
        System.out.println("For this to work, the MP3 properties of the audio files must be populated.");
        System.out.println("\nThe file are categorized in three ways for selection:");
        System.out.println("\tBy genre");
        System.out.println("\tBy artist");
        System.out.println("\tBy folder");
        System.out.println("\nThe program also has the option of saving summaries of found genres and artists, and of displaying existing playlists.");
        System.out.println("\nThe runtime options are:");
        System.out.println("\t-h this Help summary");
        System.out.println("\t-d : debug");
        System.out.println("\t-g : summarize and save collected genres to a text file");
        System.out.println("\t-a : summarize and save collected artists to a text file");
        System.out.println("\t-p <file name without delimiters> : show mp3 file properties (for debugging)");
        System.out.println("\t-ys : show playlists with artist listing");
        System.out.println("\t-yd : show playlists with artist and song detail");
    }
}
