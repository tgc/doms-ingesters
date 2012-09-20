/*
 * $Id$
 * $Revision$
 * $Date$
 * $Author$
 *
 * The DOMS project.
 * Copyright (C) 2007-2010  The State and University Library
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package dk.statsbiblioteket.doms.ingesters.radiotv;

import dk.statsbiblioteket.doms.client.DomsWSClient;
import dk.statsbiblioteket.doms.client.DomsWSClientImpl;
import dk.statsbiblioteket.doms.client.exceptions.NoObjectFound;
import dk.statsbiblioteket.doms.client.exceptions.ServerOperationFailed;
import dk.statsbiblioteket.doms.client.exceptions.XMLParseException;
import dk.statsbiblioteket.doms.client.relations.LiteralRelation;
import dk.statsbiblioteket.doms.client.relations.Relation;
import dk.statsbiblioteket.doms.client.utils.FileInfo;
import dk.statsbiblioteket.util.xml.DOM;
import dk.statsbiblioteket.util.xml.XPathSelector;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** On added xml files with radio/tv metadata, add objects to DOMS describing these files. */
public class RadioTVMetadataProcessor implements HotFolderScannerClient {

    private static final String RITZAU_ORIGINALS_ELEMENT = "//program/originals/ritzau_original";
    private static final String GALLUP_ORIGINALS_ELEMENT = "//program/originals/gallup_original";
    private static final String HAS_METAFILE_RELATION_TYPE
            = "http://doms.statsbiblioteket.dk/relations/default/0/1/#hasShard";
    private static final String CONSISTS_OF_RELATION_TYPE
            = "http://doms.statsbiblioteket.dk/relations/default/0/1/#consistsOf";
    private static final String RECORDING_PBCORE_DESCRIPTION_DOCUMENT_ELEMENT
            = "//program/pbcore/pbc:PBCoreDescriptionDocument";
    private static final String RECORDING_FILES_FILE_ELEMENT = "//program/program_recording_files/file";
    private static final String FILE_URL_ELEMENT = "file_url";
    private static final String FILE_NAME_ELEMENT = "file_name";
    private static final String FORMAT_URI_ELEMENT = "format_uri";
    private static final String MD5_SUM_ELEMENT = "md5_sum";

    private static final String PROGRAM_TEMPLATE_PID = "doms:Template_Program";
    private static final String PROGRAM_PBCORE_DS_ID = "PBCORE";
    private static final String RITZAU_ORIGINAL_DS_ID = "RITZAU_ORIGINAL";
    private static final String GALLUP_ORIGINAL_DS_ID = "GALLUP_ORIGINAL";
    private static final String META_FILE_TEMPLATE_PID = "doms:Template_Shard";
    private static final String META_FILE_METADATA_DS_ID = "SHARD_METADATA";
    private static final String RADIO_TV_FILE_TEMPLATE_PID = "doms:Template_RadioTVFile";

    private static final int MAX_FAIL_COUNT = 3;

    private static final XPathSelector XPATH_SELECTOR = DOM
            .createXPathSelector("pbc", "http://www.pbcore.org/PBCore/PBCoreNamespace.html");
    private static final String COMMENT = "Ingest of Radio/TV data";
    private static final String FAILED_COMMENT = COMMENT + ": Something failed, rolling back";
    private int exceptionCount = 0;

    /** Information used for contacting DOMS. */
    private final DOMSLoginInfo domsLoginInfo;
    /** Folder to move failed files to. */
    private final File failedFilesFolder;
    /** Folder to move processed files to. */
    private final File processedFilesFolder;

    /** Document builder that fails on XML files not conforming to the preingest schema. */
    private final DocumentBuilder preingestFilesBuilder;
    /** Document builder that does not require a specific schema. */
    private final DocumentBuilder unSchemaedBuilder;
    /** Client for communicating with DOMS. */
    private DomsWSClient _domsClient;


    /**
     * Initialise the processor.
     *
     * @param domsLoginInfo Information used for contacting DOMS.
     * @param failedFilesFolder Folder to move failed files to.
     * @param processedFilesFolder Folder to move processed files to.
     * @param preIngestFileSchema Schema for Raio/TV metadata to process.
     */
    public RadioTVMetadataProcessor(DOMSLoginInfo domsLoginInfo, File failedFilesFolder, File processedFilesFolder,
                                    Schema preIngestFileSchema) {
        this.domsLoginInfo = domsLoginInfo;
        this.failedFilesFolder = failedFilesFolder;
        this.processedFilesFolder = processedFilesFolder;

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilderFactory unschemaedFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setSchema(preIngestFileSchema);
        documentBuilderFactory.setNamespaceAware(true);
        try {
            preingestFilesBuilder = documentBuilderFactory.newDocumentBuilder();
            unSchemaedBuilder = unschemaedFactory.newDocumentBuilder();
        } catch (ParserConfigurationException pce) {
            pce.printStackTrace();
            fatalException();
            throw new RuntimeException();// will never be reached, but no matter
        }

        ErrorHandler documentErrorHandler = new org.xml.sax.ErrorHandler() {

            @Override
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }
        };
        preingestFilesBuilder.setErrorHandler(documentErrorHandler);

    }

    /**
     * Will parse the metadata and add relevant objects to DOMS.
     * @param addedFile Full path to the new file.
     */
    @Override
    public void fileAdded(File addedFile) {
        List<String> pidsToPublish = new ArrayList<String>();
        try {

            final Document radioTVMetadata = preingestFilesBuilder.parse(addedFile);

            createRecord(radioTVMetadata, addedFile, getDomsClient(), pidsToPublish);

            //This method acts as fault barrier
        } catch (SAXException se) {
            failed(addedFile, pidsToPublish, getDomsClient());

            se.printStackTrace();
            // TODO: Log this
            // TODO: we should not allow endless failures....
            // TODO: Wounded > many = FATAL
            incrementFailedTries();

        } catch (IOException ioe) {
            failed(addedFile, pidsToPublish, getDomsClient());
            ioe.printStackTrace();
            // TODO: Log this
            // TODO: we should not allow endless failures....
            // a code error for ingestMetaFile
        } catch (ServerOperationFailed se) {
            failed(addedFile, pidsToPublish, getDomsClient());

            // Failed calling the DOMS server
            se.printStackTrace();
            // TODO: Log this
            // TODO: FATAL, POSSIBLE RETIRES
            // TODO: we should not allow endless failures....

        } catch (XPathExpressionException xpee) {
            failed(addedFile, pidsToPublish, getDomsClient());

            // Failed parsing the Radio-TV XML document...
            xpee.printStackTrace();
            // TODO: Log this
            // TODO: we should not allow endless failures....
            // TODO: should _NEVER_ happend, code is broken

        } catch (URISyntaxException use) {
            // Failed parsing the Radio-TV XML document...

            failed(addedFile, pidsToPublish, getDomsClient());

            use.printStackTrace();
            // TODO: Log this
            // TODO: we should not allow endless failures....
            // failure can be from fileIngest as a pre ingest error
            // or from ingestMetaFile as a config/code error

        } catch (Exception fnfe) {
            // Handle anything unanticipated.

            failed(addedFile, pidsToPublish, getDomsClient());
            // TODO: Log this.
            fnfe.printStackTrace();
        }

    }

    /* (non-Javadoc)
     * @see dk.statsbiblioteket.doms.ingesters.radiotv.HotFolderScannerClient#fileDeleted(java.io.File)
     */
    public void fileDeleted(File deletedFile) {
        // Not relevant.
    }

    /* (non-Javadoc)
     * @see dk.statsbiblioteket.doms.ingesters.radiotv.HotFolderScannerClient#fileModified(java.io.File)
     */

    public void fileModified(File modifiedFile) {
        // Not relevant.
    }

    /**
     * Get a DOMS client for communicating with DOMS.
     * @return The DOMS client.
     */
    private DomsWSClient getDomsClient() {
        if (_domsClient == null) {
            _domsClient = new DomsWSClientImpl();
            _domsClient.setCredentials(domsLoginInfo.getDomsWSAPIUrl(), domsLoginInfo.getLogin(),
                                       domsLoginInfo.getPassword());

        }
        return _domsClient;
    }

    private String alreadyExistsInRepo(Document radioTVMetadata, DomsWSClient domsClient)
            throws XPathExpressionException, ServerOperationFailed, NoObjectFound {

        String oldId = getOldIdentifier(radioTVMetadata);
        List<String> pids = domsClient.getPidFromOldIdentifier(oldId);
        if (!pids.isEmpty()) {
            return pids.get(0);
        } else {
            throw new NoObjectFound("No object found");
        }


    }

    private String getOldIdentifier(Document radioTVMetadata) throws XPathExpressionException {

        Node radioTVPBCoreElement = XPATH_SELECTOR
                .selectNode(radioTVMetadata, RECORDING_PBCORE_DESCRIPTION_DOCUMENT_ELEMENT);

        Node oldPIDNode = XPATH_SELECTOR
                .selectNode(radioTVPBCoreElement, "pbc:pbcoreIdentifier[pbc:identifierSource=\"id\"]/pbc:identifier");

        //TODO what if the node is not set?
        if (oldPIDNode != null) {
            return oldPIDNode.getTextContent();
        } else {
            return null;
        }
    }

    private void createRecord(Document radioTVMetadata, File addedFile, DomsWSClient domsClient,
                              List<String> pidsToPublish)
            throws IOException, ServerOperationFailed, URISyntaxException, XPathExpressionException, XMLParseException {

        String originalPid;
        try {
            originalPid = alreadyExistsInRepo(radioTVMetadata, getDomsClient());
        } catch (NoObjectFound noObjectFound) {
            originalPid = null;
        }

        // TODO: Fill out metadata for file
        List<String> filePIDs = ingestFiles(radioTVMetadata, domsClient);
        pidsToPublish.addAll(filePIDs);
        writePIDs(failedFilesFolder, addedFile, pidsToPublish);

        // TODO: Fill out datastreams in program instead
        String shardPid = null;
        if (originalPid != null) { //program already exists
            shardPid = getShardPidFromProgram(originalPid, domsClient);
        }
        final String metaFilePID = ingestMetaFile(radioTVMetadata, filePIDs, shardPid, domsClient);
        pidsToPublish.add(metaFilePID);
        writePIDs(failedFilesFolder, addedFile, pidsToPublish);

        // TODO: May require some updates?
        final String programPID = ingestProgram(radioTVMetadata, metaFilePID, originalPid, domsClient);
        pidsToPublish.add(programPID);
        final File allWrittenPIDs = writePIDs(failedFilesFolder, addedFile, pidsToPublish);

        domsClient.publishObjects(COMMENT, pidsToPublish.toArray(new String[pidsToPublish.size()]));

        // The ingest was successful, if we make it here...
        // Move the processed file to the finished files folder.
        moveFile(addedFile, processedFilesFolder);

        // And it is now safe to delete the "in progress" PID file.
        allWrittenPIDs.delete();

    }

    /**
     * queries the list of relations in the program, and extracts the shard-pid from these
     *
     * @param pid        the program object pid
     * @param domsClient the client
     * @return the shard pid, or null if none found
     *
     * @throws ServerOperationFailed if something failed
     */
    private String getShardPidFromProgram(String pid, DomsWSClient domsClient) throws ServerOperationFailed {
        List<Relation> shardrelations = domsClient.listObjectRelations(pid, HAS_METAFILE_RELATION_TYPE);
        return shardrelations.size() > 0 ? shardrelations.get(0).getSubjectPid() : null;
    }

    /**
     * Iteratively (over)write the pid the <code>InProcessPIDs</code> file
     * associated with the <code>preIngestFile</code>.
     *
     * @param outputDirectory absolute path to the directory where the PID file must be
     *                        written.
     * @param preIngestFile   The file containing the Metadata for a program.
     * @param PIDs            A list of PIDs to write.
     * @return The <code>File</code> which the PIDs were written to.
     *
     * @throws IOException thrown if the file cannot be written to.
     */
    private File writePIDs(File outputDirectory, File preIngestFile, List<String> PIDs) throws IOException {
        final File pidFile = new File(outputDirectory, preIngestFile.getName() + ".InProcessPIDs");

        if (!pidFile.exists()) {
            pidFile.createNewFile();
        }

        if (pidFile.canWrite()) {
            final PrintWriter writer = new PrintWriter(pidFile);
            for (String currentPID : PIDs) {
                writer.println(currentPID);
            }
            writer.flush();
            writer.close();
            return pidFile;
        } else {
            throw new IOException("Cannot write file " + pidFile.getAbsolutePath());
        }
    }

    /**
     * Move the failed pre-ingest file to the "failedFilesFolder" folder and
     * rename the in-progress PID list file to a failed PID list file, also
     * stored in the "failedFilesFolder" folder.
     * <p/>
     * This method should be called before "pulling the plug".
     *
     * @param addedFile     The file attempted to have added to the doms
     * @param pidsToPublish The failed PIDs
     * @param domsClient    The DOMS where the PID post was attempted
     */
    private void failed(File addedFile, List<String> pidsToPublish, DomsWSClient domsClient) {
        try {
            moveFile(addedFile, failedFilesFolder);

            // Rename the in-progress PIDs to failed PIDs.
            writeFailedPIDs(addedFile, failedFilesFolder);
            domsClient.deleteObjects(FAILED_COMMENT, pidsToPublish.toArray(new String[pidsToPublish.size()]));
        } catch (Exception exception) {
            // If this bail-out error handling fails, then nothing can save
            // us...
            exception.printStackTrace(System.err);
        }
    }

    /**
     * Ingests or update a program object
     *
     * @param radioTVMetadata Bibliographical metadata about the program.
     * @param metafilePID     PID to the metafile which represents the program data.
     * @param existingPid     the existing pid of the program object, or null if it does not exist
     * @param domsClient      the client
     * @return PID of the newly created program object, created by the DOMS.
     *
     * @throws ServerOperationFailed    if creation or manipulation of the program object fails.
     * @throws XPathExpressionException if any errors were encountered while processing the
     *                                  <code>radioTVMetadata</code> XML document.
     */
    private String ingestProgram(Document radioTVMetadata, String metafilePID, String existingPid,
                                 DomsWSClient domsClient)
            throws ServerOperationFailed, XPathExpressionException, XMLParseException {

        // First, fetch the PBCore metadata document node from the pre-ingest
        // document.

        Node radioTVPBCoreElement = XPATH_SELECTOR
                .selectNode(radioTVMetadata, RECORDING_PBCORE_DESCRIPTION_DOCUMENT_ELEMENT);

        // Extract the ritzauID from the pre-ingest file.
        List<String> listOfOldPIDs = new ArrayList<String>();
        String oldId = getOldIdentifier(radioTVMetadata);
        if (oldId != null) {
            listOfOldPIDs.add(oldId);
        }

        String programObjectPID;
        if (existingPid == null) {//not Exist
            // Create a program object in the DOMS and update the PBCore metadata
            // datastream with the PBCore metadata from the pre-ingest file.
            programObjectPID = domsClient.createObjectFromTemplate(PROGRAM_TEMPLATE_PID, listOfOldPIDs, COMMENT);
            // Create relations to the metafile/shard
            domsClient.addObjectRelation(programObjectPID, HAS_METAFILE_RELATION_TYPE, metafilePID, COMMENT);

        } else { //Exists
            domsClient.unpublishObjects(COMMENT, existingPid);
            programObjectPID = existingPid;
        }

        final Document pbCoreDataStreamDocument = createPBCoreDocForDoms(radioTVPBCoreElement);
        domsClient.updateDataStream(programObjectPID, PROGRAM_PBCORE_DS_ID, pbCoreDataStreamDocument, COMMENT);

        // Get the program title from the PBCore metadata and use that as the
        // object label for this program object.
        Node titleNode = XPATH_SELECTOR
                .selectNode(radioTVPBCoreElement, "pbc:pbcoreTitle[pbc:titleType=\"titel\"]/pbc:title");
        final String programTitle = titleNode.getTextContent();
        domsClient.setObjectLabel(programObjectPID, programTitle, COMMENT);

        // Get the Ritzau metadata from the pre-ingest document and add it to
        // the Ritzau metadata data stream of the program object.
        final Document ritzauOriginalDocument = createRitzauDocument(radioTVMetadata);
        domsClient.updateDataStream(programObjectPID, RITZAU_ORIGINAL_DS_ID, ritzauOriginalDocument, COMMENT);

        // Add the Gallup metadata
        Document gallupOriginalDocument = createGallupDocument(radioTVMetadata);
        domsClient.updateDataStream(programObjectPID, GALLUP_ORIGINAL_DS_ID, gallupOriginalDocument, COMMENT);

        return programObjectPID;
    }

    /**
     * Utility method to create the Ritzau document to ingest
     *
     * @param radioTVMetadata Bibliographical metadata about the program.
     * @return A document containing the Ritzau metadata.
     */
    private Document createRitzauDocument(Document radioTVMetadata) {
        final Node ritzauPreingestElement = XPATH_SELECTOR.selectNode(radioTVMetadata, RITZAU_ORIGINALS_ELEMENT);

        // Build a Ritzau data document for the Ritzau data stream in the
        // program object.
        final Document ritzauOriginalDocument = unSchemaedBuilder.newDocument();
        final Element ritzauOriginalRootElement = ritzauOriginalDocument.createElement("ritzau_original");

        ritzauOriginalRootElement.setAttribute("xmlns", "http://doms.statsbiblioteket.dk/types/ritzau_original/0/1/#");
        ritzauOriginalDocument.appendChild(ritzauOriginalRootElement);

        ritzauOriginalRootElement.setTextContent(ritzauPreingestElement.getTextContent());
        return ritzauOriginalDocument;
    }

    /**
     * Utility method to create the Gallup document to ingest
     *
     * @param radioTVMetadata Bibliographical metadata about the program.
     * @return A document containing the Gallup metadata
     */
    private Document createGallupDocument(Node radioTVMetadata) {
        final Node gallupPreingestElement = XPATH_SELECTOR.selectNode(radioTVMetadata, GALLUP_ORIGINALS_ELEMENT);

        final Document gallupOriginalDocument = unSchemaedBuilder.newDocument();

        final Element gallupOriginalRootElement = gallupOriginalDocument.createElement("gallup_original");

        gallupOriginalRootElement.setAttribute("xmlns", "http://doms.statsbiblioteket.dk/types/gallup_original/0/1/#");

        gallupOriginalRootElement.setTextContent(gallupPreingestElement.getTextContent());

        gallupOriginalDocument.appendChild(gallupOriginalRootElement);
        return gallupOriginalDocument;
    }

    /**
     * Utility method to create the PBCore document to ingest
     *
     * @param radioTVPBCoreElement Element containing PBCore.
     * @return A document containing the PBCore metadata
     */
    private Document createPBCoreDocForDoms(Node radioTVPBCoreElement) {
        final Document pbCoreDataStreamDocument = unSchemaedBuilder.newDocument();

        // Import the PBCore metadata from the pre-ingest document and use it as
        // the contents for the PBCore metadata data stream of the program
        // object.
        final Node newPBCoreElement = pbCoreDataStreamDocument.importNode(radioTVPBCoreElement, true);

        pbCoreDataStreamDocument.appendChild(newPBCoreElement);
        return pbCoreDataStreamDocument;
    }

    /**
     * Ingest a metafile (aka. shard) object which represents the program data
     * (i.e. video and/or audio). A metafile may consist of data chunks from
     * multiple physical files identified by the PIDs in the
     * <code>filePIDs</code> list. The metadata provided by
     * <code>radioTVMetadata</code> contains, among other things, information
     * about location and duration of the chunks of data from the physical files
     * which constitutes the contents of the metafile.
     * <p/>
     * TODO: Consider cleaning up/consolidating the exceptions
     *
     * @param radioTVMetadata Metadata about location and duration of the relevant data
     *                        chunks from the physical files.
     * @param filePIDs        List of PIDs for the physical files containing the data
     *                        represented by this metafile.
     * @param metaFilePID     The PID of the object, if it exists already. Otherwise null
     * @return PID of the newly created metafile object, created by the DOMS.
     *
     * @throws ServerOperationFailed    if creation or manipulation of the metafile object fails.
     * @throws IOException              if io exceptions occur while communicating.
     * @throws XPathExpressionException if any errors were encountered while processing the
     *                                  <code>radioTVMetadata</code> XML document.
     * @throws URISyntaxException       if shard URL could not be generated
     */
    private String ingestMetaFile(Document radioTVMetadata, List<String> filePIDs, String metaFilePID,
                                  DomsWSClient domsClient)
            throws ServerOperationFailed, IOException, XPathExpressionException, URISyntaxException, XMLParseException {

        if (metaFilePID == null) {
            // Create a file object from the file object template.
            metaFilePID = domsClient.createObjectFromTemplate(META_FILE_TEMPLATE_PID, COMMENT);
            // TODO: Do something about this.
            final FileInfo fileInfo = new FileInfo("shard/" + metaFilePID,
                                                   new URL("http://www.statsbiblioteket.dk/doms/shard/" + metaFilePID),
                                                   "", new URI("info:pronom/fmt/199"));

            domsClient.addFileToFileObject(metaFilePID, fileInfo, COMMENT);

        } else {
            domsClient.unpublishObjects(COMMENT, metaFilePID);
        }

        final Document metadataDataStreamDocument = unSchemaedBuilder.newDocument();

        final Element metadataDataStreamRootElement = metadataDataStreamDocument.createElement("shard_metadata");

        metadataDataStreamDocument.appendChild(metadataDataStreamRootElement);

        // Add all the "file" elements from the radio-tv metadata document.
        // TODO: Note that this is just a first-shot implementation until a
        // proper metadata format has been defined.
        final NodeList recordingFileElements = XPATH_SELECTOR
                .selectNodeList(radioTVMetadata, RECORDING_FILES_FILE_ELEMENT);

        for (int fileElementIdx = 0; fileElementIdx < recordingFileElements.getLength(); fileElementIdx++) {

            final Node currentRecordingFile = recordingFileElements.item(fileElementIdx);

            // Append to the end of the list of child nodes.
            final Node newMetadataElement = metadataDataStreamDocument.importNode(currentRecordingFile, true);

            metadataDataStreamRootElement.appendChild(newMetadataElement);
        }

        domsClient.updateDataStream(metaFilePID, META_FILE_METADATA_DS_ID, metadataDataStreamDocument, COMMENT);

        List<Relation> relations = domsClient.listObjectRelations(metaFilePID, CONSISTS_OF_RELATION_TYPE);
        HashSet<String> existingRels = new HashSet<String>();
        for (Relation relation : relations) {
            if (!filePIDs.contains(relation.getSubjectPid())) {
                domsClient.removeObjectRelation((LiteralRelation) relation, COMMENT);
            } else {
                existingRels.add(relation.getSubjectPid());
            }
        }
        for (String filePID : filePIDs) {
            if (!existingRels.contains(filePID)) {
                domsClient.addObjectRelation(metaFilePID, CONSISTS_OF_RELATION_TYPE, filePID, COMMENT);

            }
        }

        return metaFilePID;
    }

    /**
     * Ingest any missing file objects into the DOMS and return a list of PIDs
     * for all the DOMS file objects corresponding to the files listed in the
     * <code>radioTVMetadata</code> document.
     *
     * @param radioTVMetadata Metadata XML document containing the file information.
     * @param domsClient      The client used for ingesting to DOMS
     * @return A <code>List</code> of PIDs of the radio-tv file objects created
     *         by the DOMS.
     *
     * @throws XPathExpressionException if any errors were encountered while processing the
     *                                  <code>radioTVMetadata</code> XML document.
     * @throws MalformedURLException    if a file element contains an invalid URL.
     * @throws ServerOperationFailed    if creation and retrieval of a radio-tv file object fails.
     * @throws URISyntaxException       if the format URI for the file is invalid.
     */
    private List<String> ingestFiles(Document radioTVMetadata, DomsWSClient domsClient)
            throws XPathExpressionException, MalformedURLException, ServerOperationFailed, URISyntaxException {

        // Get the recording files XML element and process the file information.
        final NodeList recordingFiles = XPATH_SELECTOR.selectNodeList(radioTVMetadata, RECORDING_FILES_FILE_ELEMENT);

        // Ensure that the DOMS contains a file object for each recording file
        // element in the radio-tv XML document.
        final List<String> fileObjectPIDs = new ArrayList<String>();
        for (int nodeIndex = 0; nodeIndex < recordingFiles.getLength(); nodeIndex++) {

            final Node currentFileNode = recordingFiles.item(nodeIndex);

            final Node fileURLNode = XPATH_SELECTOR.selectNode(currentFileNode, FILE_URL_ELEMENT);
            final String fileURLString = fileURLNode.getTextContent();
            final URL fileURL = new URL(fileURLString);

            String fileObjectPID;
            try {
                fileObjectPID = domsClient.getFileObjectPID(fileURL);
            } catch (NoObjectFound nof) {
                // The DOMS contains no file object for this file URL.
                // Create a new one now.
                final Node fileNameNode = XPATH_SELECTOR.selectNode(currentFileNode, FILE_NAME_ELEMENT);

                final String fileName = fileNameNode.getTextContent();

                final Node formatURINode = XPATH_SELECTOR.selectNode(currentFileNode, FORMAT_URI_ELEMENT);

                final URI formatURI = new URI(formatURINode.getTextContent());

                final Node md5SumNode = XPATH_SELECTOR.selectNode(currentFileNode, MD5_SUM_ELEMENT);

                // The MD5 check sum is optional. Just leave it empty if the
                // pre-ingest file does not provide it.
                String md5String = "";
                if (md5SumNode != null) {
                    md5String = md5SumNode.getTextContent();
                }

                final FileInfo fileInfo = new FileInfo(fileName, fileURL, md5String, formatURI);

                fileObjectPID = domsClient.createFileObject(RADIO_TV_FILE_TEMPLATE_PID, fileInfo, COMMENT);
            }
            fileObjectPIDs.add(fileObjectPID);
        }
        return fileObjectPIDs;
    }

    /**
     * Move <code>fileToMove</code> to the folder specified by
     * <code>destinationFolder</code>.
     *
     * @param fileToMove        Path to the file to move to <code>destinationFolder</code>.
     * @param destinationFolder Path of the destination folder to move the file to.
     */
    private void moveFile(File fileToMove, File destinationFolder) {
        fileToMove.renameTo(new File(destinationFolder.getAbsolutePath() + File.separator + fileToMove.getName()));
    }

    private void writeFailedPIDs(File failedMetadataFile, File directory) {
        final File activePIDsFile = new File(directory, failedMetadataFile.getName() + ".InProcessPIDs");
        final File failedPIDsFile = new File(directory, failedMetadataFile.getName() + ".failedPIDs");
        activePIDsFile.renameTo(failedPIDsFile);
    }

    /**
     * ends all attempts to ingest from the current list of file descriptions in
     * the pre-ingest file Violent exit needed "system.exit()
     */
    private void fatalException() {
        System.exit(-1);
    }

    /** The number of tries is incremented by one */
    private void incrementFailedTries() {
        exceptionCount += 1;
        if (exceptionCount >= MAX_FAIL_COUNT) {
            System.err.println("Too many errors in ingest. Exiting.");
            fatalException();
        }
    }

}