/*******************************************************************************
 * Copyright (c) 2020 Konduit K.K.
 * Copyright (c) 2015-2019 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.deeplearning4j.examples.multigpu.advanced.w2vsentiment;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.embeddings.wordvectors.WordVectors;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.common.resources.Downloader;
import org.slf4j.Logger;

import java.io.*;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * This class generates binary datasets out of raw text for further use on gpu for faster tuning sessions.
 * Idea behind this approach is simple: if you're going to run over the same corpus over and over, it's more efficient
 * to do all preprocessing once, and save datasets as binary files, instead of doing the same stuff over and over on the fly
 *
 */
public class DataSetsBuilder {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DataSetsBuilder.class);

    /** Data URL for downloading */
    public static final String DATA_URL = "http://ai.stanford.edu/~amaas/data/sentiment/aclImdb_v1.tar.gz";
    /** Location to save and extract the training/testing data */
    public static final String DATA_PATH = FilenameUtils.concat(System.getProperty("java.io.tmpdir"), "dl4j_w2vSentiment/");
    /** Location (local file system) for the Google News vectors. Set this manually. */
    public static String wordVectorsPath = "/PATH/TO/YOUR/VECTORS/GoogleNews-vectors-negative300.bin.gz";

    public static final String TRAIN_PATH = FilenameUtils.concat(System.getProperty("user.home"), "dl4j-examples-data/imdbpresaved/train");
    public static final String TEST_PATH = FilenameUtils.concat(System.getProperty("user.home"), "dl4j-examples-data/imdbpresaved/test");

    @Parameter(names = {"-b","--batch"}, description = "BatchSize")
    private int batchSize = 64;

    @Parameter(names = {"-l","--length"}, description = "Truncate max review length to")
    private int truncateReviewsToLength = 256;

    public void run(String[] args) throws Exception {
        JCommander jcmdr = new JCommander(this);
        try {
            jcmdr.parse(args);
        } catch (ParameterException e) {
            //User provides invalid input -> print the usage info
            jcmdr.usage();
            try {
                Thread.sleep(500);
            } catch (Exception e2) {
            }
            System.exit(1);
        }

        if (wordVectorsPath.startsWith("/PATH/TO/YOUR/VECTORS/")) {
            System.out.println("wordVectorsPath has not been set. Checking default location in ~/dl4j-examples-data for download...");
            checkDownloadW2VECModel();
        }
        //Download and extract data
        downloadData();

        WordVectors wordVectors = WordVectorSerializer.loadStaticModel(new File(wordVectorsPath));
        SentimentExampleIterator train = new SentimentExampleIterator(DATA_PATH, wordVectors, batchSize, truncateReviewsToLength, true);
        SentimentExampleIterator test = new SentimentExampleIterator(DATA_PATH, wordVectors, batchSize, truncateReviewsToLength, false);

        log.info("Saving test data...");
        saveDatasets(test, TEST_PATH);

        log.info("Saving train data...");
        saveDatasets(train, TRAIN_PATH);
    }

    private static void downloadData() throws Exception {
        //Create directory if required
        File directory = new File(DATA_PATH);
        if(!directory.exists()) directory.mkdir();

        //Download file:
        String archizePath = DATA_PATH + "aclImdb_v1.tar.gz";
        File archiveFile = new File(archizePath);
        String extractedPath = DATA_PATH + "aclImdb";
        File extractedFile = new File(extractedPath);

        if( !archiveFile.exists() ){
            System.out.println("Starting data download (80MB)...");
            FileUtils.copyURLToFile(new URL(DATA_URL), archiveFile);
            System.out.println("Data (.tar.gz file) downloaded to " + archiveFile.getAbsolutePath());
            //Extract tar.gz file to output directory
            extractTarGz(archizePath, DATA_PATH);
        } else {
            //Assume if archive (.tar.gz) exists, then data has already been extracted
            System.out.println("Data (.tar.gz file) already exists at " + archiveFile.getAbsolutePath());
            if( !extractedFile.exists()){
                //Extract tar.gz file to output directory
                extractTarGz(archizePath, DATA_PATH);
            } else {
                System.out.println("Data (extracted) already exists at " + extractedFile.getAbsolutePath());
            }
        }
    }

    private static final int BUFFER_SIZE = 4096;
    private static void extractTarGz(String filePath, String outputPath) throws IOException {
        int fileCount = 0;
        int dirCount = 0;
        System.out.print("Extracting files");
        try(TarArchiveInputStream tais = new TarArchiveInputStream(
            new GzipCompressorInputStream( new BufferedInputStream( new FileInputStream(filePath))))){
            TarArchiveEntry entry;

            /** Read the tar entries using the getNextEntry method **/
            while ((entry = (TarArchiveEntry) tais.getNextEntry()) != null) {
                //System.out.println("Extracting file: " + entry.getName());

                //Create directories as required
                if (entry.isDirectory()) {
                    new File(outputPath + entry.getName()).mkdirs();
                    dirCount++;
                }else {
                    int count;
                    byte data[] = new byte[BUFFER_SIZE];

                    FileOutputStream fos = new FileOutputStream(outputPath + entry.getName());
                    BufferedOutputStream dest = new BufferedOutputStream(fos,BUFFER_SIZE);
                    while ((count = tais.read(data, 0, BUFFER_SIZE)) != -1) {
                        dest.write(data, 0, count);
                    }
                    dest.close();
                    fileCount++;
                }
                if(fileCount % 1000 == 0) System.out.print(".");
            }
        }

        System.out.println("\n" + fileCount + " files and " + dirCount + " directories extracted to: " + outputPath);
    }

    protected void saveDatasets(DataSetIterator iterator, String dir) {
        AtomicInteger counter = new AtomicInteger(0);
        new File(dir).mkdirs();
        while (iterator.hasNext()) {
            String path = FilenameUtils.concat(dir, "dataset-" + (counter.getAndIncrement()) + ".bin");
            iterator.next().save(new File(path));

            if (counter.get() % 500 == 0)
                log.info("{} datasets saved so far...", counter.get());
        }
    }


    public static void checkDownloadW2VECModel() throws IOException {
        String defaultwordVectorsPath = FilenameUtils.concat(System.getProperty("user.home"), "dl4j-examples-data/w2vec300");
        String md5w2vec = "1c892c4707a8a1a508b01a01735c0339";
        wordVectorsPath = new File(defaultwordVectorsPath, "GoogleNews-vectors-negative300.bin.gz").getAbsolutePath();
        if (new File(wordVectorsPath).exists()) {
            System.out.println("\n\tGoogleNews-vectors-negative300.bin.gz file found at path: " + defaultwordVectorsPath);
            System.out.println("\tChecking md5 of existing file..");
            if (Downloader.checkMD5OfFile(md5w2vec, new File(wordVectorsPath))) {
                System.out.println("\tExisting file hash matches.");
                return;
            } else {
                System.out.println("\tExisting file hash doesn't match. Retrying download...");
            }
        } else {
            System.out.println("\n\tNo previous download of GoogleNews-vectors-negative300.bin.gz found at path: " + defaultwordVectorsPath);
        }
        System.out.println("\tWARNING: GoogleNews-vectors-negative300.bin.gz is a 1.5GB file.");
        System.out.println("\tPress \"ENTER\" to start a download of GoogleNews-vectors-negative300.bin.gz to " + defaultwordVectorsPath);
        Scanner scanner = new Scanner(System.in);
        scanner.nextLine();
        System.out.println("Starting model download (1.5GB!)...");
        Downloader.download("Word2Vec", new URL("https://dl4jdata.blob.core.windows.net/resources/wordvectors/GoogleNews-vectors-negative300.bin.gz"), new File(wordVectorsPath), md5w2vec, 5);
        System.out.println("Successfully downloaded word2vec model to " + wordVectorsPath);
    }

    public static void main(String[] args) throws Exception {
        new DataSetsBuilder().run(args);
    }
}
