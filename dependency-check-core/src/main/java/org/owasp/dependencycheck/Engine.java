/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.owasp.dependencycheck.analyzer.AnalysisPhase;
import org.owasp.dependencycheck.analyzer.Analyzer;
import org.owasp.dependencycheck.analyzer.AnalyzerService;
import org.owasp.dependencycheck.analyzer.FileTypeAnalyzer;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.cpe.CpeMemoryIndex;
import org.owasp.dependencycheck.data.cpe.IndexException;
import org.owasp.dependencycheck.data.nvdcve.ConnectionFactory;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.data.update.CachedWebDataSource;
import org.owasp.dependencycheck.data.update.UpdateService;
import org.owasp.dependencycheck.data.update.exception.UpdateException;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.NoDataException;
import org.owasp.dependencycheck.utils.FileUtils;
import org.owasp.dependencycheck.utils.InvalidSettingException;
import org.owasp.dependencycheck.utils.Settings;

/**
 * Scans files, directories, etc. for Dependencies. Analyzers are loaded and used to process the files found by the
 * scan, if a file is encountered and an Analyzer is associated with the file type then the file is turned into a
 * dependency.
 *
 * @author Jeremy Long <jeremy.long@owasp.org>
 */
public class Engine implements Serializable {

    /**
     * The list of dependencies.
     */
    private List<Dependency> dependencies;
    /**
     * A Map of analyzers grouped by Analysis phase.
     */
    private transient final EnumMap<AnalysisPhase, List<Analyzer>> analyzers;
    /**
     * A Map of analyzers grouped by Analysis phase.
     */
    private transient final Set<FileTypeAnalyzer> fileTypeAnalyzers;
    /**
     * The ClassLoader to use when dynamically loading Analyzer and Update services.
     */
    private transient ClassLoader serviceClassLoader;
    /**
     * The Logger for use throughout the class.
     */
    private transient static final Logger LOGGER = Logger.getLogger(Engine.class.getName());

    /**
     * Creates a new Engine.
     *
     * @throws DatabaseException thrown if there is an error connecting to the database
     */
    public Engine() throws DatabaseException {
        this(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a new Engine using the specified classloader to dynamically load Analyzer and Update services.
     *
     * @param serviceClassLoader the ClassLoader to use when dynamically loading Analyzer and Update services
     * @throws DatabaseException thrown if there is an error connecting to the database
     */
    public Engine(ClassLoader serviceClassLoader) throws DatabaseException {
        this.dependencies = new ArrayList<Dependency>();
        this.analyzers = new EnumMap<AnalysisPhase, List<Analyzer>>(AnalysisPhase.class);
        this.fileTypeAnalyzers = new HashSet<FileTypeAnalyzer>();
        this.serviceClassLoader = serviceClassLoader;

        ConnectionFactory.initialize();

        boolean autoUpdate = true;
        try {
            autoUpdate = Settings.getBoolean(Settings.KEYS.AUTO_UPDATE);
        } catch (InvalidSettingException ex) {
            LOGGER.log(Level.FINE, "Invalid setting for auto-update; using true.");
        }
        if (autoUpdate) {
            doUpdates();
        }
        loadAnalyzers();
    }

    /**
     * Properly cleans up resources allocated during analysis.
     */
    public void cleanup() {
        ConnectionFactory.cleanup();
    }

    /**
     * Loads the analyzers specified in the configuration file (or system properties).
     */
    private void loadAnalyzers() {

        for (AnalysisPhase phase : AnalysisPhase.values()) {
            analyzers.put(phase, new ArrayList<Analyzer>());
        }

        final AnalyzerService service = new AnalyzerService(serviceClassLoader);
        final Iterator<Analyzer> iterator = service.getAnalyzers();
        while (iterator.hasNext()) {
            final Analyzer a = iterator.next();
            analyzers.get(a.getAnalysisPhase()).add(a);
            if (a instanceof FileTypeAnalyzer) {
                this.fileTypeAnalyzers.add((FileTypeAnalyzer) a);
            }
        }
    }

    /**
     * Get the List of the analyzers for a specific phase of analysis.
     *
     * @param phase the phase to get the configured analyzers.
     * @return the analyzers loaded
     */
    public List<Analyzer> getAnalyzers(AnalysisPhase phase) {
        return analyzers.get(phase);
    }

    /**
     * Get the dependencies identified.
     *
     * @return the dependencies identified
     */
    public List<Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies;
        //for (Dependency dependency: dependencies) {
        //    dependencies.add(dependency);
        //}
    }

    /**
     * Scans an array of files or directories. If a directory is specified, it will be scanned recursively. Any
     * dependencies identified are added to the dependency collection.
     *
     * @param paths an array of paths to files or directories to be analyzed
     * @return the list of dependencies scanned
     *
     * @since v0.3.2.5
     */
    public List<Dependency> scan(String[] paths) {
        List<Dependency> deps = new ArrayList<Dependency>();
        for (String path : paths) {
            final File file = new File(path);
            List<Dependency> d = scan(file);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a given file or directory. If a directory is specified, it will be scanned recursively. Any dependencies
     * identified are added to the dependency collection.
     *
     * @param path the path to a file or directory to be analyzed
     * @return the list of dependencies scanned
     */
    public List<Dependency> scan(String path) {
        if (path.matches("^.*[\\/]\\*\\.[^\\/:*|?<>\"]+$")) {
            final String[] parts = path.split("\\*\\.");
            final String[] ext = new String[]{parts[parts.length - 1]};
            final File dir = new File(path.substring(0, path.length() - ext[0].length() - 2));
            if (dir.isDirectory()) {
                final List<File> files = (List<File>) org.apache.commons.io.FileUtils.listFiles(dir, ext, true);
                return scan(files);
            } else {
                final String msg = String.format("Invalid file path provided to scan '%s'", path);
                LOGGER.log(Level.SEVERE, msg);
            }
        } else {
            final File file = new File(path);
            return scan(file);
        }
        return null;
    }

    /**
     * Scans an array of files or directories. If a directory is specified, it will be scanned recursively. Any
     * dependencies identified are added to the dependency collection.
     *
     * @param files an array of paths to files or directories to be analyzed.
     * @return the list of dependencies
     *
     * @since v0.3.2.5
     */
    public List<Dependency> scan(File[] files) {
        List<Dependency> deps = new ArrayList<Dependency>();
        for (File file : files) {
            List<Dependency> d = scan(file);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a list of files or directories. If a directory is specified, it will be scanned recursively. Any
     * dependencies identified are added to the dependency collection.
     *
     * @param files a set of paths to files or directories to be analyzed
     * @return the list of dependencies scanned
     *
     * @since v0.3.2.5
     */
    public List<Dependency> scan(Set<File> files) {
        List<Dependency> deps = new ArrayList<Dependency>();
        for (File file : files) {
            List<Dependency> d = scan(file);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a list of files or directories. If a directory is specified, it will be scanned recursively. Any
     * dependencies identified are added to the dependency collection.
     *
     * @param files a set of paths to files or directories to be analyzed
     * @return the list of dependencies scanned
     *
     * @since v0.3.2.5
     */
    public List<Dependency> scan(List<File> files) {
        List<Dependency> deps = new ArrayList<Dependency>();
        for (File file : files) {
            List<Dependency> d = scan(file);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a given file or directory. If a directory is specified, it will be scanned recursively. Any dependencies
     * identified are added to the dependency collection.
     *
     * @param file the path to a file or directory to be analyzed
     * @return the list of dependencies scanned
     *
     * @since v0.3.2.4
     *
     */
    public List<Dependency> scan(File file) {
        if (file.exists()) {
            if (file.isDirectory()) {
                return scanDirectory(file);
            } else {
                Dependency d = scanFile(file);
                if (d != null) {
                    List<Dependency> deps = new ArrayList<Dependency>();
                    deps.add(d);
                    return deps;
                }
            }
        }
        return null;
    }

    /**
     * Recursively scans files and directories. Any dependencies identified are added to the dependency collection.
     *
     * @param dir the directory to scan.
     */
    protected List<Dependency> scanDirectory(File dir) {
        final File[] files = dir.listFiles();
        List<Dependency> deps = new ArrayList<Dependency>();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    List<Dependency> d = scanDirectory(f);
                    if (d != null) {
                        deps.addAll(d);
                    }
                } else {
                    Dependency d = scanFile(f);
                    deps.add(d);
                }
            }
        }
        return deps;
    }

    /**
     * Scans a specified file. If a dependency is identified it is added to the dependency collection.
     *
     * @param file The file to scan
     * @return the scanned dependency
     */
    protected Dependency scanFile(File file) {
        if (!file.isFile()) {
            final String msg = String.format("Path passed to scanFile(File) is not a file: %s. Skipping the file.", file.toString());
            LOGGER.log(Level.FINE, msg);
            return null;
        }
        final String fileName = file.getName();
        final String extension = FileUtils.getFileExtension(fileName);
        Dependency dependency = null;
        if (extension != null) {
            if (supportsExtension(extension)) {
                dependency = new Dependency(file);
                dependencies.add(dependency);
            }
        } else {
            final String msg = String.format("No file extension found on file '%s'. The file was not analyzed.", file.toString());
            LOGGER.log(Level.FINEST, msg);
        }
        return dependency;
    }

    /**
     * Runs the analyzers against all of the dependencies.
     */
    public void analyzeDependencies() {
        //need to ensure that data exists
        try {
            ensureDataExists();
        } catch (NoDataException ex) {
            final String msg = String.format("%s%n%nUnable to continue dependency-check analysis.", ex.getMessage());
            LOGGER.log(Level.SEVERE, msg);
            LOGGER.log(Level.FINE, null, ex);
            return;
        } catch (DatabaseException ex) {
            final String msg = String.format("%s%n%nUnable to continue dependency-check analysis.", ex.getMessage());
            LOGGER.log(Level.SEVERE, msg);
            LOGGER.log(Level.FINE, null, ex);
            return;

        }

        final String logHeader = String.format("%n"
                + "----------------------------------------------------%n"
                + "BEGIN ANALYSIS%n"
                + "----------------------------------------------------");
        LOGGER.log(Level.FINE, logHeader);
        LOGGER.log(Level.INFO, "Analysis Starting");

        // analysis phases
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            final List<Analyzer> analyzerList = analyzers.get(phase);

            for (Analyzer a : analyzerList) {
                initializeAnalyzer(a);

                /* need to create a copy of the collection because some of the
                 * analyzers may modify it. This prevents ConcurrentModificationExceptions.
                 * This is okay for adds/deletes because it happens per analyzer.
                 */
                final String msg = String.format("Begin Analyzer '%s'", a.getName());
                LOGGER.log(Level.FINE, msg);
                final Set<Dependency> dependencySet = new HashSet<Dependency>();
                dependencySet.addAll(dependencies);
                for (Dependency d : dependencySet) {
                    boolean shouldAnalyze = true;
                    if (a instanceof FileTypeAnalyzer) {
                        final FileTypeAnalyzer fAnalyzer = (FileTypeAnalyzer) a;
                        shouldAnalyze = fAnalyzer.supportsExtension(d.getFileExtension());
                    }
                    if (shouldAnalyze) {
                        final String msgFile = String.format("Begin Analysis of '%s'", d.getActualFilePath());
                        LOGGER.log(Level.FINE, msgFile);
                        try {
                            a.analyze(d, this);
                        } catch (AnalysisException ex) {
                            final String exMsg = String.format("An error occurred while analyzing '%s'.", d.getActualFilePath());
                            LOGGER.log(Level.WARNING, exMsg);
                            LOGGER.log(Level.FINE, "", ex);
                        } catch (Throwable ex) {
                            final String axMsg = String.format("An unexpected error occurred during analysis of '%s'", d.getActualFilePath());
                            //final AnalysisException ax = new AnalysisException(axMsg, ex);
                            LOGGER.log(Level.WARNING, axMsg);
                            LOGGER.log(Level.FINE, "", ex);
                        }
                    }
                }
            }
        }
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            final List<Analyzer> analyzerList = analyzers.get(phase);

            for (Analyzer a : analyzerList) {
                closeAnalyzer(a);
            }
        }

        final String logFooter = String.format("%n"
                + "----------------------------------------------------%n"
                + "END ANALYSIS%n"
                + "----------------------------------------------------");
        LOGGER.log(Level.FINE, logFooter);
        LOGGER.log(Level.INFO, "Analysis Complete");
    }

    /**
     * Initializes the given analyzer.
     *
     * @param analyzer the analyzer to initialize
     */
    private void initializeAnalyzer(Analyzer analyzer) {
        try {
            final String msg = String.format("Initializing %s", analyzer.getName());
            LOGGER.log(Level.FINE, msg);
            analyzer.initialize();
        } catch (Throwable ex) {
            final String msg = String.format("Exception occurred initializing %s.", analyzer.getName());
            LOGGER.log(Level.SEVERE, msg);
            LOGGER.log(Level.FINE, null, ex);
            try {
                analyzer.close();
            } catch (Throwable ex1) {
                LOGGER.log(Level.FINEST, null, ex1);
            }
        }
    }

    /**
     * Closes the given analyzer.
     *
     * @param analyzer the analyzer to close
     */
    private void closeAnalyzer(Analyzer analyzer) {
        final String msg = String.format("Closing Analyzer '%s'", analyzer.getName());
        LOGGER.log(Level.FINE, msg);
        try {
            analyzer.close();
        } catch (Throwable ex) {
            LOGGER.log(Level.FINEST, null, ex);
        }
    }

    /**
     * Cycles through the cached web data sources and calls update on all of them.
     */
    private void doUpdates() {
        final UpdateService service = new UpdateService(serviceClassLoader);
        final Iterator<CachedWebDataSource> iterator = service.getDataSources();
        while (iterator.hasNext()) {
            final CachedWebDataSource source = iterator.next();
            try {
                source.update();
            } catch (UpdateException ex) {
                LOGGER.log(Level.WARNING,
                        "Unable to update Cached Web DataSource, using local data instead. Results may not include recent vulnerabilities.");
                LOGGER.log(Level.FINE, String.format("Unable to update details for %s", source.getClass().getName()), ex);
            }
        }
    }

    /**
     * Returns a full list of all of the analyzers. This is useful for reporting which analyzers where used.
     *
     * @return a list of Analyzers
     */
    public List<Analyzer> getAnalyzers() {
        final List<Analyzer> ret = new ArrayList<Analyzer>();
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            final List<Analyzer> analyzerList = analyzers.get(phase);
            ret.addAll(analyzerList);
        }
        return ret;
    }

    /**
     * Checks all analyzers to see if an extension is supported.
     *
     * @param ext a file extension
     * @return true or false depending on whether or not the file extension is supported
     */
    public boolean supportsExtension(String ext) {
        if (ext == null) {
            return false;
        }
        boolean scan = false;
        for (FileTypeAnalyzer a : this.fileTypeAnalyzers) {
            /* note, we can't break early on this loop as the analyzers need to know if
             they have files to work on prior to initialization */
            scan |= a.supportsExtension(ext);
        }
        return scan;
    }

    /**
     * Checks the CPE Index to ensure documents exists. If none exist a NoDataException is thrown.
     *
     * @throws NoDataException thrown if no data exists in the CPE Index
     * @throws DatabaseException thrown if there is an exception opening the database
     */
    private void ensureDataExists() throws NoDataException, DatabaseException {
        final CpeMemoryIndex cpe = CpeMemoryIndex.getInstance();
        final CveDB cve = new CveDB();

        try {
            cve.open();
            cpe.open(cve);
        } catch (IndexException ex) {
            throw new NoDataException(ex.getMessage(), ex);
        } catch (DatabaseException ex) {
            throw new NoDataException(ex.getMessage(), ex);
        } finally {
            cve.close();
        }
        if (cpe.numDocs() <= 0) {
            cpe.close();
            throw new NoDataException("No documents exist");
        }
    }
}
