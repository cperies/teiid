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


package org.teiid.connector.xml.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.teiid.connector.api.ConnectorException;
import org.teiid.connector.api.ExecutionContext;

import com.metamatrix.connector.xml.ResultProducer;
import com.metamatrix.connector.xml.base.CriteriaDesc;
import com.metamatrix.connector.xml.base.ExecutionInfo;
import com.metamatrix.connector.xml.base.QueryAnalyzer;

public class HTTPExecutor implements ResultProducer {

    protected List<HTTPRequest> requests = new ArrayList<HTTPRequest>();
    protected HTTPConnectorState state;
	protected ExecutionInfo executionInfo;
	protected HTTPManagedConnectionFactory config;
	protected ExecutionContext context;

    /**
     * @param state
     * @param execution
     * @param exeInfo 
     * @param analyzer 
     * @throws ConnectorException
     */
    public HTTPExecutor(HTTPConnectorState state, ExecutionInfo exeInfo, QueryAnalyzer analyzer, HTTPManagedConnectionFactory config, ExecutionContext context)
            throws ConnectorException {
        this.executionInfo = exeInfo;
        this.state = state;
        this.config = config;
        this.context = context;
        
        List<CriteriaDesc[]> requestPerms = analyzer.getRequestPerms();
        
        createRequests(exeInfo, requestPerms);     
    }

	@Override
    public Iterator<com.metamatrix.connector.xml.Document> getXMLDocuments() throws ConnectorException {
		ArrayList<com.metamatrix.connector.xml.Document> result = new ArrayList<com.metamatrix.connector.xml.Document>();
		
        for(HTTPRequest request : this.requests) {
        	com.metamatrix.connector.xml.Document doc = request.getDocumentStream();
        	result.add(doc);
        }
        return result.iterator();
		
	}
	
	@Override
	public void closeStreams() {
        for(HTTPRequest request : this.requests) {
        	if (request != null) {
        		request.release();
        		request = null;
        	}
        }
	}
	
	protected void createRequests(ExecutionInfo exeInfo, List<CriteriaDesc[]> requestPerms) throws ConnectorException {
		for (CriteriaDesc[] criteria : requestPerms) {
        	List<CriteriaDesc> criteriaList = Arrays.asList(criteria);
        	HTTPRequest request = new HTTPRequest(this.state, exeInfo, criteriaList, this.config, this.context);
        	this.requests.add(request);
        }
	}
}