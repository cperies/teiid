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

package org.teiid.dqp.internal.process.multisource;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.common.buffer.TupleSource;
import org.teiid.core.id.IDGenerator;
import org.teiid.dqp.internal.process.DQPWorkContext;
import org.teiid.dqp.internal.process.Request;
import org.teiid.query.analysis.AnalysisRecord;
import org.teiid.query.metadata.QueryMetadataInterface;
import org.teiid.query.metadata.TempCapabilitiesFinder;
import org.teiid.query.metadata.TempMetadataAdapter;
import org.teiid.query.metadata.TempMetadataStore;
import org.teiid.query.optimizer.QueryOptimizer;
import org.teiid.query.optimizer.TestOptimizer;
import org.teiid.query.optimizer.capabilities.BasicSourceCapabilities;
import org.teiid.query.optimizer.capabilities.CapabilitiesFinder;
import org.teiid.query.optimizer.capabilities.FakeCapabilitiesFinder;
import org.teiid.query.processor.HardcodedDataManager;
import org.teiid.query.processor.ProcessorDataManager;
import org.teiid.query.processor.ProcessorPlan;
import org.teiid.query.processor.RegisterRequestParameter;
import org.teiid.query.processor.TestProcessor;
import org.teiid.query.resolver.TestResolver;
import org.teiid.query.rewriter.QueryRewriter;
import org.teiid.query.sql.lang.Command;
import org.teiid.query.sql.symbol.ElementSymbol;
import org.teiid.query.sql.visitor.ElementCollectorVisitor;
import org.teiid.query.unittest.RealMetadataFactory;
import org.teiid.query.util.CommandContext;
import org.teiid.translator.SourceSystemFunctions;

/** 
 * It's important here that the MultiSourceCapabilityFinder is used since some capabilities 
 * will never be pushed to the source
 * 
 * @since 4.2
 */
@SuppressWarnings("nls")
public class TestMultiSourcePlanToProcessConverter {
    
    private final class MultiSourceDataManager extends HardcodedDataManager {
        
        public MultiSourceDataManager() {
            setMustRegisterCommands(false);
        }

        public TupleSource registerRequest(CommandContext context, Command command, String modelName, RegisterRequestParameter parameterObject) throws org.teiid.core.TeiidComponentException {
        	assertNotNull(parameterObject.connectorBindingId);
        	
        	Collection<ElementSymbol> elements = ElementCollectorVisitor.getElements(command, true, true);
            
        	for (ElementSymbol symbol : elements) {
                if (symbol.getMetadataID() instanceof MultiSourceElement) {
                    fail("Query Contains a MultiSourceElement -- MultiSource expansion did not happen"); //$NON-NLS-1$
                }
            }
            return super.registerRequest(context, command, modelName, parameterObject);
        }
    }

    private static final boolean DEBUG = false;

    public void helpTestMultiSourcePlan(QueryMetadataInterface metadata, String userSql, String multiModel, int sourceCount, ProcessorDataManager dataMgr, List<?>[] expectedResults, VDBMetaData vdb) throws Exception {
    	helpTestMultiSourcePlan(metadata, userSql, multiModel, sourceCount, dataMgr, expectedResults, vdb, null);
    }
    
    public void helpTestMultiSourcePlan(QueryMetadataInterface metadata, String userSql, String multiModel, int sourceCount, ProcessorDataManager dataMgr, List<?>[] expectedResults, VDBMetaData vdb, List<?> params) throws Exception {
     
        Set<String> multiSourceModels = vdb.getMultiSourceModelNames();
        for (String model:multiSourceModels) {
            char sourceID = 'a';
            // by default every model has one binding associated, but for multi-source there were none assigned. 
            ModelMetaData m = vdb.getModel(model);
            int x = m.getSourceNames().size();
            for(int i=x; i<sourceCount; i++, sourceID++) {
            	 m.addSourceMapping("" + sourceID, "translator",  null); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String elementName = vdb.getPropertyValue(Request.MULTISOURCE_COLUMN_NAME);
        if (elementName == null) {
        	elementName = MultiSourceElement.DEFAULT_MULTI_SOURCE_ELEMENT_NAME;
        }
        QueryMetadataInterface wrapper = new MultiSourceMetadataWrapper(metadata, multiSourceModels, elementName);
        wrapper = new TempMetadataAdapter(wrapper, new TempMetadataStore());
    	DQPWorkContext dqpContext = RealMetadataFactory.buildWorkContext(wrapper, vdb);
        
        AnalysisRecord analysis = new AnalysisRecord(false, DEBUG);
        
        Command command = TestResolver.helpResolve(userSql, wrapper);               
                
        // Plan
        command = QueryRewriter.rewrite(command, wrapper, null);
        FakeCapabilitiesFinder fakeFinder = new FakeCapabilitiesFinder();
        BasicSourceCapabilities bsc = TestOptimizer.getTypicalCapabilities();
        bsc.setFunctionSupport(SourceSystemFunctions.CONCAT, true);
        fakeFinder.addCapabilities(multiModel, bsc); 
        
        CapabilitiesFinder finder = new MultiSourceCapabilitiesFinder(fakeFinder, multiSourceModels);
        finder = new TempCapabilitiesFinder(finder);
        IDGenerator idGenerator = new IDGenerator();
        
        CommandContext context = new CommandContext("0", "test", "user", null, vdb.getName(), vdb.getVersion(), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        context.setPlanToProcessConverter(new MultiSourcePlanToProcessConverter(wrapper, idGenerator, analysis, finder, multiSourceModels, elementName, dqpContext, context));

        ProcessorPlan plan = QueryOptimizer.optimizePlan(command, wrapper, idGenerator, finder, analysis, context);
                        
        if(DEBUG) {
            System.out.println("\nMultiSource Plan:"); //$NON-NLS-1$
            System.out.println(plan);
        }
        if (params != null) {
        	TestProcessor.setParameterValues(params, command, context);
        }
        TestProcessor.helpProcess(plan, context, dataMgr, expectedResults);
    }

    @Test public void testNoReplacement() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT * FROM MultiModel.Phys WHERE SOURCE_NAME = 'bogus'"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = 
            new List[0];
        final ProcessorDataManager dataMgr = new MultiSourceDataManager();
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }

    @Test public void testSingleReplacement() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT * FROM MultiModel.Phys WHERE SOURCE_NAME = 'a'"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = 
            new List<?>[] { Arrays.asList(new Object[] { null, null, "a"}) };
        final HardcodedDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(false);
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testSingleReplacementAltName() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT * FROM MultiModel.Phys WHERE foo = 'a'"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = 
            new List<?>[] { Arrays.asList(new Object[] { null, null, "a"}) };
        final HardcodedDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(false);
        VDBMetaData vdb = RealMetadataFactory.exampleMultiBindingVDB();
        vdb.addProperty(Request.MULTISOURCE_COLUMN_NAME, "foo");
		helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, vdb);
    }
    
    @Test public void testPreparedReplacement() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT * FROM MultiModel.Phys WHERE SOURCE_NAME = ?"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = 
            new List<?>[] { Arrays.asList(new Object[] { null, null, "a"}) };
        final HardcodedDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(false);
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB(), Arrays.asList("a"));
    }

    @Test public void testMultiReplacement() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT * FROM MultiModel.Phys"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = 
            new List<?>[] { Arrays.asList(new Object[] { null, null, "a"}),
                         Arrays.asList(new Object[] { null, null, "b"}),
                         Arrays.asList(new Object[] { null, null, "c"})};
        final ProcessorDataManager dataMgr = new MultiSourceDataManager();
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testMultiReplacementWithOrderBy() throws Exception {
        QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();

        final String userSql = "SELECT a, b, source_name || a FROM MultiModel.Phys order by a"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = new List<?>[] {
            Arrays.asList("e", "z", "be"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Arrays.asList("f", "z", "bf"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Arrays.asList("x", "z", "ax"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Arrays.asList("y", "z", "ay"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        final HardcodedDataManager dataMgr = new HardcodedDataManager();
        dataMgr.addData("SELECT g_0.a, g_0.b, concat('a', g_0.a) FROM MultiModel.Phys AS g_0", //$NON-NLS-1$
                        new List<?>[] {
                            Arrays.asList("y", "z", "ay"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            Arrays.asList("x", "z", "ax")}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        dataMgr.addData("SELECT g_0.a, g_0.b, concat('b', g_0.a) FROM MultiModel.Phys AS g_0", //$NON-NLS-1$
                        new List<?>[] {
                            Arrays.asList("e", "z", "be"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            Arrays.asList("f", "z", "bf")}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }

    @Test public void testMultiReplacementWithLimit() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT distinct a, b, source_name || a FROM MultiModel.Phys order by a limit 1"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = new List<?>[] {
            Arrays.asList("e", "z", "be"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        final HardcodedDataManager dataMgr = new HardcodedDataManager();
        dataMgr.addData("SELECT g_0.a, g_0.b, concat('a', g_0.a) FROM MultiModel.Phys AS g_0", //$NON-NLS-1$
                new List<?>[] {
                    Arrays.asList("y", "z", "ay"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    Arrays.asList("x", "z", "ax")}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        dataMgr.addData("SELECT g_0.a, g_0.b, concat('b', g_0.a) FROM MultiModel.Phys AS g_0", //$NON-NLS-1$
                new List<?>[] {
                    Arrays.asList("e", "z", "be"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    Arrays.asList("f", "z", "bf")}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testMultiReplacementWithProjectConstantLimit() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "SELECT a, b, source_name || a, '1' FROM MultiModel.Phys"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = new List<?>[] {
            Arrays.asList("x", "z", "ax", "1"),
        };
        final HardcodedDataManager dataMgr = new HardcodedDataManager();
        dataMgr.addData("SELECT g_0.a, g_0.b, concat('a', g_0.a) FROM MultiModel.Phys AS g_0", //$NON-NLS-1$
                new List<?>[] {
                    Arrays.asList("x", "z", "ax")}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        dataMgr.addData("SELECT g_0.a, g_0.b, concat('b', g_0.a) FROM MultiModel.Phys AS g_0", //$NON-NLS-1$
                new List<?>[] {});
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testMultiDependentJoin() throws Exception {
        QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        
        final String userSql = "SELECT a.a FROM MultiModel.Phys a inner join MultiModel.Phys b makedep on (a.a = b.a) order by a"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 2;
        final List<?>[] expected = 
            new List<?>[] { Arrays.asList(new Object[] { "x"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "x"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "x"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "x"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "y"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "y"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "y"}), //$NON-NLS-1$
                         Arrays.asList(new Object[] { "y"})}; //$NON-NLS-1$
                         
        final HardcodedDataManager dataMgr = new HardcodedDataManager();
        dataMgr.addData("SELECT g_0.a FROM MultiModel.Phys AS g_0",  //$NON-NLS-1$
                        new List<?>[] { Arrays.asList(new Object[] { "x" }), //$NON-NLS-1$
                                     Arrays.asList(new Object[] { "y" })}); //$NON-NLS-1$
        dataMgr.addData("SELECT g_0.a FROM MultiModel.Phys AS g_0 WHERE g_0.a IN ('x', 'y')",  //$NON-NLS-1$
                        new List<?>[] { Arrays.asList(new Object[] { "x" }), //$NON-NLS-1$
                                     Arrays.asList(new Object[] { "y" })}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testSingleReplacementInDynamicCommand() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "exec Virt.sq1('a')"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(new Object[] { null, null}), };
        final ProcessorDataManager dataMgr = new MultiSourceDataManager();
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testSingleReplacementInDynamicCommandNullValue() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "exec Virt.sq1(null)"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List[0];
        final ProcessorDataManager dataMgr = new MultiSourceDataManager();
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testMultiUpdateAll() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "update MultiModel.Phys set a = '1' where b = 'z'"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(3)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        dataMgr.addData("UPDATE MultiModel.Phys SET a = '1' WHERE b = 'z'", new List<?>[] {Arrays.asList(1)}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testInsertMatching() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "INSERT INTO MultiModel.Phys(a, SOURCE_NAME) VALUES ('a', 'a')"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(1)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        dataMgr.addData("INSERT INTO MultiModel.Phys (a) VALUES ('a')", new List<?>[] {Arrays.asList(1)}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testInsertNotMatching() throws Exception {
        final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "INSERT INTO MultiModel.Phys(a, SOURCE_NAME) VALUES ('a', 'x')"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(0)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testInsertAll() throws Exception {
    	final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "INSERT INTO MultiModel.Phys(a) VALUES ('a')"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(3)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        dataMgr.addData("INSERT INTO MultiModel.Phys (a) VALUES ('a')", new List<?>[] {Arrays.asList(1)}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testProcedure() throws Exception {
    	final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "exec MultiModel.proc('b', 'a')"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(1)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        dataMgr.addData("EXEC MultiModel.proc('b')", new List<?>[] {Arrays.asList(1)}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testProcedureAll() throws Exception {
    	final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "exec MultiModel.proc(\"in\"=>'b')"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(1), Arrays.asList(1), Arrays.asList(1)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        dataMgr.addData("EXEC MultiModel.proc('b')", new List<?>[] {Arrays.asList(1)}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }
    
    @Test public void testTempInsert() throws Exception {
    	final QueryMetadataInterface metadata = RealMetadataFactory.exampleMultiBinding();
        final String userSql = "INSERT INTO #x select * from MultiModel.Phys"; //$NON-NLS-1$
        final String multiModel = "MultiModel"; //$NON-NLS-1$
        final int sources = 3;
        final List<?>[] expected = new List<?>[] { Arrays.asList(3)};
        final MultiSourceDataManager dataMgr = new MultiSourceDataManager();
        dataMgr.setMustRegisterCommands(true);
        dataMgr.addData("SELECT g_0.a, g_0.b FROM MultiModel.Phys AS g_0", new List<?>[] {Arrays.asList("a", "b")}); //$NON-NLS-1$
        helpTestMultiSourcePlan(metadata, userSql, multiModel, sources, dataMgr, expected, RealMetadataFactory.exampleMultiBindingVDB());
    }

}
