/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.jboss;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.msc.service.ServiceController;
import org.jboss.vfs.VirtualFile;
import org.teiid.adminapi.Model;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.adminapi.impl.VDBMetadataParser;
import org.teiid.deployers.UDFMetaData;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.VdbConstants;
import org.teiid.metadata.index.IndexMetadataStore;
import org.xml.sax.SAXException;


/**
 * This file loads the "vdb.xml" file inside a ".vdb" file, along with all the metadata in the .INDEX files
 */
class VDBParserDeployer implements DeploymentUnitProcessor {
	
	public VDBParserDeployer() {
	}
	
	public void deploy(final DeploymentPhaseContext phaseContext)  throws DeploymentUnitProcessingException {
		DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
		if (!TeiidAttachments.isVDBDeployment(deploymentUnit)) {
			return;
		}

		VirtualFile file = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
		
		if (TeiidAttachments.isDynamicVDB(deploymentUnit)) {
			parseVDBXML(file, deploymentUnit, phaseContext).setDynamic(true);			
		}
		else {
			// scan for different files 
			List<VirtualFile> childFiles = file.getChildren();
			for (VirtualFile childFile:childFiles) {
				scanVDB(childFile, deploymentUnit, phaseContext);
			}
			
			mergeMetaData(deploymentUnit);
		}
	}
	
	private void scanVDB(VirtualFile file, DeploymentUnit deploymentUnit, DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
		if (file.isDirectory()) {
			List<VirtualFile> childFiles = file.getChildren();
			for (VirtualFile childFile:childFiles) {
				scanVDB(childFile, deploymentUnit, phaseContext);
			}
		}
		else {
			if (file.getName().toLowerCase().equals(VdbConstants.DEPLOYMENT_FILE)) {
				parseVDBXML(file, deploymentUnit, phaseContext);
			}
			else if (file.getName().endsWith(VdbConstants.INDEX_EXT)) {
				IndexMetadataStore imf = deploymentUnit.getAttachment(TeiidAttachments.INDEX_METADATA);
				if (imf == null) {
					imf = new IndexMetadataStore();
					deploymentUnit.putAttachment(TeiidAttachments.INDEX_METADATA, imf);
				}
				imf.addIndexFile(file);
			}
			else if (file.getName().toLowerCase().endsWith(VdbConstants.MODEL_EXT)) {
				UDFMetaData udf = deploymentUnit.getAttachment(TeiidAttachments.UDF_METADATA);
				if (udf == null) {
					udf = new UDFMetaData();
					deploymentUnit.putAttachment(TeiidAttachments.UDF_METADATA, udf);
				}
				udf.addModelFile(file);				
			}
		}
	}

	private VDBMetaData parseVDBXML(VirtualFile file, DeploymentUnit deploymentUnit, DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
		try {
			VDBMetadataParser.validate(file.openStream());
			VDBMetaData vdb = VDBMetadataParser.unmarshell(file.openStream());
			ServiceController<?> sc = phaseContext.getServiceRegistry().getService(TeiidServiceNames.OBJECT_SERIALIZER);
			ObjectSerializer serializer = ObjectSerializer.class.cast(sc.getValue());
			if (serializer.buildVdbXml(vdb).exists()) {
				vdb = VDBMetadataParser.unmarshell(new FileInputStream(serializer.buildVdbXml(vdb)));
			}
			deploymentUnit.putAttachment(TeiidAttachments.VDB_METADATA, vdb);
			LogManager.logDetail(LogConstants.CTX_RUNTIME,"VDB "+file.getName()+" has been parsed.");  //$NON-NLS-1$ //$NON-NLS-2$
			return vdb;
		} catch (XMLStreamException e) {
			throw new DeploymentUnitProcessingException(IntegrationPlugin.Event.TEIID50017.name(), e);
		} catch (IOException e) {
			throw new DeploymentUnitProcessingException(IntegrationPlugin.Event.TEIID50017.name(), e);
		} catch (SAXException e) {
			throw new DeploymentUnitProcessingException(IntegrationPlugin.Event.TEIID50017.name(), e);
		}
	}
	
    public void undeploy(final DeploymentUnit context) {
    }	
    
	protected VDBMetaData mergeMetaData(DeploymentUnit deploymentUnit) throws DeploymentUnitProcessingException {
		VDBMetaData vdb = deploymentUnit.getAttachment(TeiidAttachments.VDB_METADATA);
		UDFMetaData udf = deploymentUnit.getAttachment(TeiidAttachments.UDF_METADATA);
		IndexMetadataStore imf = deploymentUnit.getAttachment(TeiidAttachments.INDEX_METADATA);
		
		VirtualFile file = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
		if (vdb == null) {
			LogManager.logError(LogConstants.CTX_RUNTIME, IntegrationPlugin.Util.gs(IntegrationPlugin.Event.TEIID50016,file.getName())); 
			return null;
		}
		
		try {
			vdb.setUrl(file.toURL());		
			
			// build the metadata store
			if (imf != null) {
				imf.addEntriesPlusVisibilities(file, vdb);
					
				// This time stamp is used to check if the VDB is modified after the metadata is written to disk
				//vdb.addProperty(VDBService.VDB_LASTMODIFIED_TIME, String.valueOf(file.getLastModified()));
			}
			
			if (udf != null) {
				// load the UDF
				for(Model model:vdb.getModels()) {
					if (model.getModelType().equals(Model.Type.FUNCTION)) {
						String path = ((ModelMetaData)model).getPath();
						if (path == null) {
							throw new DeploymentUnitProcessingException(IntegrationPlugin.Util.gs(IntegrationPlugin.Event.TEIID50075, model.getName()));
						}
						udf.buildFunctionModelFile(model.getName(), path);
					}
				}		
			}
		} catch(IOException e) {
			throw new DeploymentUnitProcessingException(IntegrationPlugin.Event.TEIID50017.name(), e); 
		} catch (XMLStreamException e) {
			throw new DeploymentUnitProcessingException(IntegrationPlugin.Event.TEIID50017.name(), e);
		}
				
		LogManager.logTrace(LogConstants.CTX_RUNTIME, "VDB", file.getName(), "has been parsed."); //$NON-NLS-1$ //$NON-NLS-2$
		return vdb;
	}
}
