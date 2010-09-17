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

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.ws.BindingProvider;

import org.w3c.dom.Document;

import dk.statsbiblioteket.doms.centralWebservice.CentralWebservice;
import dk.statsbiblioteket.doms.centralWebservice.CentralWebserviceService;
import dk.statsbiblioteket.util.xml.DOM;

/**
 * @author tsh
 * 
 */
public class DOMSClient {

    private CentralWebservice domsAPI;

    public void login(URL domsWSAPIEndpoint, String userName, String password) {
	domsAPI = new CentralWebserviceService(domsWSAPIEndpoint, new QName(
	        "http://central.doms.statsbiblioteket.dk/",
	        "CentralWebserviceService")).getCentralWebservicePort();

	Map<String, Object> domsAPILogin = ((BindingProvider) domsAPI)
	        .getRequestContext();
	domsAPILogin.put(BindingProvider.USERNAME_PROPERTY, userName);
	domsAPILogin.put(BindingProvider.PASSWORD_PROPERTY, password);
    }

    /**
     * Create a new DOMS object from an object template already stored in the
     * DOMS.
     * 
     * @param templatePID
     *            PID identifying the template to use.
     * @return PID of the created object.
     * @throws ServerError
     *             if the object creation failed.
     */
    public String createObjectFromTemplate(String templatePID)
	    throws ServerError {
	try {
	    return domsAPI.newObject(templatePID);
	} catch (Exception e) {
	    throw new ServerError(
		    "Failed creating a new object from template: "
		            + templatePID, e);
	}
    }

    /**
     * Create a new file object from an existing file object template, based on
     * the information provided by the <code>FileInfo</code> instance, in the
     * DOMS.
     * 
     * @param templatePID
     *            The PID of the file object template to use for creation of the
     *            new file object.
     * @param fileInfo
     *            File location, checksum and so on for the physical file
     *            associated with the object.
     * @return PID of the created file object in the DOMS.
     * @throws ServerError
     *             if the object creation failed.
     * @see FileInfo
     */
    public String createFileObject(String templatePID, FileInfo fileInfo)
	    throws ServerError {

	try {
	    final String fileObjectPID = createObjectFromTemplate(templatePID);

	    domsAPI.addFileFromPermanentURL(fileObjectPID, fileInfo
		    .getFileName(), fileInfo.getMd5Sum(), fileInfo
		    .getFileLocation().toString(), fileInfo.getFileFormatURI()
		    .toString());

	    return fileObjectPID;

	} catch (Exception e) {
	    throw new ServerError(
		    "Failed creating a new file object (template PID: "
		            + templatePID + ") from this file information: "
		            + fileInfo, e);
	}
    }

    /**
     * Get the PID of an existing file object in the DOMS which is associated
     * with the physical file specifiec by <code>fileURL</code>.
     * 
     * @param fileURL
     *            location of the physical file to find the corresponding DOMS
     *            file object for.
     * @return PID of the DOMS file object.
     * @throws NoObjectFound
     *             if there does not exist DOMS file object associated with
     *             <code>fileURL</code>.
     * @throws ServerError
     *             if any errors are encountered while looking up the file
     *             object.
     */
    public String getFileObjectPID(URL fileURL) throws NoObjectFound,
	    ServerError {

	String pid = null;
	try {
	    pid = domsAPI.getFileObjectWithURL(fileURL.toString());
	} catch (Exception exception) {
	    throw new ServerError("Unable to retrieve file object with URL: "
		    + fileURL, exception);
	}
	if (pid == null) {
	    throw new NoObjectFound("Unable to retrieve file object with URL: "
		    + fileURL);
	}
	return pid;
    }

    /**
     * 
     * @param objectPID
     * @param datastreamID
     * @return <code>Document</code> containing the datastream contents.
     * @throws ServerError
     */
    public Document getDataStream(String objectPID, String datastreamID)
	    throws ServerError {
	try {
	    final String datastreamXML = domsAPI.getDatastreamContents(
		    objectPID, datastreamID);

	    final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory
		    .newInstance();

	    final DocumentBuilder documentBuilder = documentBuilderFactory
		    .newDocumentBuilder();

	    final ByteArrayInputStream datastreamBytes = new ByteArrayInputStream(
		    datastreamXML.getBytes());
	    final Document dataStream = documentBuilder.parse(datastreamBytes);

	    return dataStream;
	} catch (Exception exception) {
	    throw new ServerError("Failed getting datastream (ID: "
		    + datastreamID + ") contents from object (PID: "
		    + objectPID + ")", exception);
	}
    }

    /**
     * 
     * @param objectPID
     * @param dataStreamID
     * @param newDataStreamContents
     * @throws ServerError
     */
    public void updateDataStream(String objectPID, String dataStreamID,
	    Document newDataStreamContents) throws ServerError {
	try {
	    domsAPI.modifyDatastream(objectPID, dataStreamID, DOM
		    .domToString(newDataStreamContents));

	} catch (Exception exception) {
	    throw new ServerError("Failed updating datastream (ID: "
		    + dataStreamID + ") contents from object (PID: "
		    + objectPID + ")", exception);
	}

    }

    /**
     * 
     * @param sourcePID
     * @param relationType
     * @param targetPID
     * @throws ServerError
     */
    public void addObjectRelation(String sourcePID, String relationType,
	    String targetPID) throws ServerError {
	try {
	    domsAPI.addRelation(sourcePID, "info:fedora/" + sourcePID,
		    relationType, "info:fedora/" + targetPID);
	} catch (Exception exception) {
	    throw new ServerError("Failed creating object relation (type: "
		    + relationType + ") from the source object (PID: "
		    + sourcePID + ") to the target object (PID: " + targetPID
		    + ")", exception);
	}
    }

    /**
     * Mark the objects identified by the the PIDs in <code>pidsToPublish</code>
     * as published, and thus viewable from the DOMS.
     * 
     * @param pidsToPublish
     *            <code>List</code> of PIDs for the objects to publish.
     * @throws ServerError
     *             if any errors are encountered while publishing the objects.
     */
    public void publishObjects(List<String> pidsToPublish) throws ServerError {
	try {
	    domsAPI.markPublishedObject(pidsToPublish);
	} catch (Exception exception) {
	    throw new ServerError("Failed marking objects as published. PIDs: "
		    + pidsToPublish, exception);
	}
    }
}
