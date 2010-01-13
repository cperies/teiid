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

package org.teiid.test.client.impl;

import java.io.File;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.teiid.test.client.ExpectedResults;
import org.teiid.test.client.ctc.ResultsHolder;
import org.teiid.test.framework.TestLogger;
import org.teiid.test.framework.exception.QueryTestFailedException;
import org.teiid.test.framework.exception.TransactionRuntimeException;

import com.metamatrix.jdbc.util.ResultSetUtil;

public class ExpectedResultsImpl implements ExpectedResults {
    
    private static final int MAX_COL_WIDTH = 65;

     
    protected Properties props;
    protected int resultMode = -1;
    protected String generateDir = null;
    protected String querySetIdentifier = null;
    protected String results_dir_loc = null;
     
    protected Map<String, ResultsHolder> loadedResults = new HashMap<String, ResultsHolder>();
    
    
    public ExpectedResultsImpl(String querySetIdentifier, Properties properties) {
    	this.props = properties;
    	this.querySetIdentifier = querySetIdentifier;
     	
    	this.results_dir_loc = props.getProperty(
				PROP_EXPECTED_RESULTS_DIR_LOC, ""); 
    	
	String expected_root_loc = this.props
		.getProperty(PROP_EXPECTED_RESULTS_ROOT_DIR);

	if (expected_root_loc != null) {
	    File dir = new File(expected_root_loc, results_dir_loc);
	    this.results_dir_loc = dir.getAbsolutePath();
	}

    	
    	TestLogger.logInfo("Expected results loc: " + this.results_dir_loc);
    }


	@Override
	public boolean isExceptionExpected(String queryidentifier) throws QueryTestFailedException {
	    return false;
 	}



	@Override
	public String getQuerySetID() {
	    return this.querySetIdentifier;
	}



	@Override
	public synchronized File getResultsFile(String queryidentifier) throws QueryTestFailedException {
		return findExpectedResultsFile(queryidentifier, this.querySetIdentifier);
		
	}
	
	
	/**
     * Compare the results of a query with those that were expected.
     * 
     * @param expectedResults
     *            The expected results.
     * @param results
     *            The actual results - may be null if <code>actualException</code>.
     * @param actualException
     *            The actual exception recieved durring query execution - may be null if <code>results</code>.
     * @param isOrdered
     *            Are the actual results ordered?
     * @param batchSize
     *            Size of the batch(es) used in determining when the first batch of results were read.
     * @return The response time for comparing the first batch (sizes) of resutls.
     * @throws QueryTestFailedException
     *             If comparison fails.
     */
    public void compareResults(  final String queryIdentifier,
    				final String sql,
                                      final ResultSet resultSet,
                                      final Throwable actualException,
                                      final int testStatus,
                                      final boolean isOrdered,
                                      final int batchSize) throws QueryTestFailedException {

	File expectedResultsFile = getResultsFile(queryIdentifier);
	
//	File temp = new File("ERROR_" + queryIdentifier + ".txt");
//	temp.deleteOnExit();
	
	List<?> results = null;
	if (actualException != null) {
	    
	    try {
		results = ResultSetUtil.writeAndCompareThrowable(actualException, null, expectedResultsFile, false);

	    } catch (Exception e1) {
		throw new TransactionRuntimeException(e1);
	    }
	    
		if (results != null && results.size() >0) {
		    throw new QueryTestFailedException("Comparison resulted in unequal lines");
		}
	} else {
	      
	    try {
		results = ResultSetUtil.writeAndCompareResultSet(resultSet, MAX_COL_WIDTH, false, null, expectedResultsFile, false);

	    } catch (Exception e) {
		throw new TransactionRuntimeException(e);
	    }	 
	    
		if (results != null && results.size() >0) {
		    throw new QueryTestFailedException("Comparison resulted in unequal lines");
		}
	}

    	
    }

	@Override
	public Object getMetaData(String queryidentifier) {
		// TODO Auto-generated method stub
		return null;
	}

	
    private File findExpectedResultsFile(String queryIdentifier,
			String querySetIdentifier) throws QueryTestFailedException {
		String resultFileName = queryIdentifier + ".txt"; //$NON-NLS-1$
		File file = new File(results_dir_loc + "/" + querySetIdentifier, resultFileName);
		if (!file.exists()) {
			throw new QueryTestFailedException("Query results file " + file.getAbsolutePath() + " cannot be found");
		}
		
		return file;

	}
  
   
}