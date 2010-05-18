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

package org.teiid.query.optimizer.relational.rules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.teiid.api.exception.query.QueryMetadataException;
import org.teiid.api.exception.query.QueryPlannerException;
import org.teiid.core.TeiidComponentException;
import org.teiid.query.analysis.AnalysisRecord;
import org.teiid.query.metadata.QueryMetadataInterface;
import org.teiid.query.optimizer.capabilities.CapabilitiesFinder;
import org.teiid.query.optimizer.relational.OptimizerRule;
import org.teiid.query.optimizer.relational.RelationalPlanner;
import org.teiid.query.optimizer.relational.RuleStack;
import org.teiid.query.optimizer.relational.plantree.NodeConstants;
import org.teiid.query.optimizer.relational.plantree.NodeEditor;
import org.teiid.query.optimizer.relational.plantree.PlanNode;
import org.teiid.query.processor.relational.JoinNode.JoinStrategyType;
import org.teiid.query.sql.lang.DependentSetCriteria;
import org.teiid.query.sql.lang.JoinType;
import org.teiid.query.sql.symbol.Expression;
import org.teiid.query.sql.util.SymbolMap;
import org.teiid.query.util.CommandContext;


/**
 * Finds nodes that can be turned into dependent joins 
 */
public final class RuleChooseDependent implements OptimizerRule {
	
	private static AtomicInteger ID = new AtomicInteger();

    private static class CandidateJoin {
        PlanNode joinNode;
        boolean leftCandidate;
        boolean rightCandidate;
    }

	public static final int DEFAULT_INDEPENDENT_CARDINALITY = 10;
    
    public PlanNode execute(PlanNode plan, QueryMetadataInterface metadata, CapabilitiesFinder capFinder, RuleStack rules, AnalysisRecord analysisRecord, CommandContext context)
        throws QueryPlannerException, QueryMetadataException, TeiidComponentException {
        
        // Find first criteria node in plan with conjuncts        
        List<CandidateJoin> matches = findCandidate(plan, metadata, analysisRecord);
        
        boolean pushCriteria = false;

        // Handle all cases where both siblings are possible matches
        for (CandidateJoin entry : matches) {
            PlanNode joinNode = entry.joinNode;
            
            PlanNode sourceNode = entry.leftCandidate?joinNode.getFirstChild():joinNode.getLastChild();
            
            PlanNode siblingNode = entry.leftCandidate?joinNode.getLastChild():joinNode.getFirstChild();
            
            boolean bothCandidates = entry.leftCandidate&&entry.rightCandidate;
            
            JoinStrategyType joinStrategy = (JoinStrategyType)joinNode.getProperty(NodeConstants.Info.JOIN_STRATEGY);
            
            PlanNode chosenNode = chooseDepWithoutCosting(sourceNode, bothCandidates?siblingNode:null, analysisRecord);
            if(chosenNode != null) {
                pushCriteria |= markDependent(chosenNode, joinNode);
                continue;
            }   
            
            float depJoinCost = NewCalculateCostUtil.computeCostForDepJoin(joinNode, !entry.leftCandidate, joinStrategy, metadata, capFinder, context);
            PlanNode dependentNode = sourceNode;
            PlanNode independentNode = siblingNode;
            
            if (bothCandidates) {
                float siblingDepJoinCost = NewCalculateCostUtil.computeCostForDepJoin(joinNode, true, joinStrategy, metadata, capFinder, context);
                if (siblingDepJoinCost != NewCalculateCostUtil.UNKNOWN_VALUE && (siblingDepJoinCost < depJoinCost || depJoinCost == NewCalculateCostUtil.UNKNOWN_VALUE)) {
                    dependentNode = siblingNode;
                    depJoinCost = siblingDepJoinCost;
                    independentNode = sourceNode;
                }
            }
            
            if (depJoinCost != NewCalculateCostUtil.UNKNOWN_VALUE) {
                pushCriteria |= decideForAgainstDependentJoin(depJoinCost, independentNode, dependentNode, joinNode, metadata, context);
            } else {
            	float sourceCost = NewCalculateCostUtil.computeCostForTree(sourceNode, metadata);
            	float siblingCost = NewCalculateCostUtil.computeCostForTree(siblingNode, metadata);
            	
                if (bothCandidates && sourceCost != NewCalculateCostUtil.UNKNOWN_VALUE && sourceCost < RuleChooseDependent.DEFAULT_INDEPENDENT_CARDINALITY 
                		&& (sourceCost < siblingCost || siblingCost == NewCalculateCostUtil.UNKNOWN_VALUE)) {
                    pushCriteria |= markDependent(siblingNode, joinNode);
                } else if (siblingCost != NewCalculateCostUtil.UNKNOWN_VALUE && siblingCost < RuleChooseDependent.DEFAULT_INDEPENDENT_CARDINALITY) {
                    pushCriteria |= markDependent(sourceNode, joinNode);
                }
            }
        }
        
        if (pushCriteria) {
            // Insert new rules to push down the SELECT criteria
            rules.push(RuleConstants.CLEAN_CRITERIA); //it's important to run clean criteria here since it will remove unnecessary dependent sets
            rules.push(RuleConstants.PUSH_SELECT_CRITERIA);
        }
        
        return plan;
    }    

    boolean decideForAgainstDependentJoin(float depJoinCost, PlanNode independentNode, PlanNode dependentNode, PlanNode joinNode, QueryMetadataInterface metadata, CommandContext context)
        throws QueryMetadataException, TeiidComponentException {
        JoinStrategyType joinStrategy = (JoinStrategyType)joinNode.getProperty(NodeConstants.Info.JOIN_STRATEGY);
        joinNode.setProperty(NodeConstants.Info.EST_DEP_JOIN_COST, new Float(depJoinCost));

        float joinCost = NewCalculateCostUtil.computeCostForJoin(independentNode, dependentNode, joinStrategy, metadata, context);
        joinNode.setProperty(NodeConstants.Info.EST_JOIN_COST, new Float(joinCost));
        if(depJoinCost < joinCost) {
            return markDependent(dependentNode, joinNode);
        }
        
        return false;
    }
        
    /**
     * Walk the tree pre-order, finding all access nodes that are candidates and
     * adding them to the matches list.
     * @param metadata Metadata implementation
     * @param node Root node to search
     * @param matches Collection to accumulate matches in
     */
    List<CandidateJoin> findCandidate(PlanNode root, QueryMetadataInterface metadata, AnalysisRecord analysisRecord) {

        List<CandidateJoin> candidates = new ArrayList<CandidateJoin>();
        
        for (PlanNode joinNode : NodeEditor.findAllNodes(root, NodeConstants.Types.JOIN, NodeConstants.Types.ACCESS)) {
            CandidateJoin candidate = null;
            
            for (Iterator j = joinNode.getChildren().iterator(); j.hasNext();) {
                PlanNode child = (PlanNode)j.next();
                child = FrameUtil.findJoinSourceNode(child);
                
                if(child.hasBooleanProperty(NodeConstants.Info.MAKE_NOT_DEP) || !isValidJoin(joinNode, child, analysisRecord)) {
                	continue;
                }
                if (candidate == null) {
                    candidate = new CandidateJoin();
                    candidate.joinNode = joinNode;
                    candidates.add(candidate);
                }
                if (j.hasNext()) {
                    candidate.leftCandidate=true;
                } else {
                    candidate.rightCandidate=true;
                }
            }
        }
        
        return candidates;
        
    }
    
    /**
     * Check whether a join is valid.  Invalid joins are CROSS JOIN, FULL OUTER JOIN,
     * any join without criteria, any join with no equality criteria, and any outer 
     * join that has the outer side not the same as the dependent.
     * @param joinNode The join node to check
     * @param sourceNode The access node being considered
     * @param analysisRecord TODO
     * @return True if valid for making dependent
     */
    boolean isValidJoin(PlanNode joinNode, PlanNode sourceNode, AnalysisRecord analysisRecord) {
        JoinType jtype = (JoinType) joinNode.getProperty(NodeConstants.Info.JOIN_TYPE);

        // Check that join is not a CROSS join or FULL OUTER join
        if(jtype.equals(JoinType.JOIN_CROSS) || jtype.equals(JoinType.JOIN_FULL_OUTER)) {
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Rejecting dependent access node as parent join is CROSS or FULL OUTER: "+ sourceNode.nodeToString()); //$NON-NLS-1$
        	}
            return false;
        }

        // Check that join criteria exist
        List jcrit = (List) joinNode.getProperty(NodeConstants.Info.JOIN_CRITERIA);
        if(jcrit == null || jcrit.size() == 0) {
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Rejecting dependent access node as parent join has no join criteria: "+ sourceNode.nodeToString()); //$NON-NLS-1$
        	}
            return false;
        }
        
        if(joinNode.getProperty(NodeConstants.Info.LEFT_EXPRESSIONS) == null) { 
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Rejecting dependent access node as parent join has no equality expressions: "+ sourceNode.nodeToString()); //$NON-NLS-1$
        	}
            return false;
        }
                        
        // Check that for a left or right outer join the dependent side must be the inner 
        if(jtype.isOuter() && JoinUtil.getInnerSideJoinNodes(joinNode)[0] != sourceNode) {
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Rejecting dependent access node as it is on outer side of a join: "+ sourceNode.nodeToString()); //$NON-NLS-1$
        	}
            return false;
        }

        return true;        
    }
    
    PlanNode chooseDepWithoutCosting(PlanNode rootNode1, PlanNode rootNode2, AnalysisRecord analysisRecord)  {
    	PlanNode sourceNode1 = FrameUtil.findJoinSourceNode(rootNode1);
        PlanNode sourceNode2 = null;
        
        if (rootNode2 != null) {
            sourceNode2 = FrameUtil.findJoinSourceNode(rootNode2);
        }
        if(sourceNode1.hasCollectionProperty(NodeConstants.Info.ACCESS_PATTERNS) ) {
            if (sourceNode2 != null && sourceNode2.hasCollectionProperty(NodeConstants.Info.ACCESS_PATTERNS) ) {
                //Return null - query planning should fail because both access nodes
                //have unsatisfied access patterns
            	if (analysisRecord.recordDebug()) {
            		analysisRecord.println("Neither access node can be made dependent because both have unsatisfied access patterns: " + sourceNode1.nodeToString() + "\n" + sourceNode2.toString()); //$NON-NLS-1$ //$NON-NLS-2$
            	}
                return null;
            }  
            return rootNode1;
        } else if (sourceNode2 != null && sourceNode2.hasCollectionProperty(NodeConstants.Info.ACCESS_PATTERNS) ) {
            //Access node 2 has unsatisfied access pattern,
            //so try to make node 2 dependent
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Making access node dependent to satisfy access pattern: "+ sourceNode2.nodeToString()); //$NON-NLS-1$
        	}
            return rootNode2;
        } 
        
        // Check for hints, which over-rule heuristics
        if(sourceNode1.hasBooleanProperty(NodeConstants.Info.MAKE_DEP)) {
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Making access node dependent due to hint: "+ sourceNode1.nodeToString());                     //$NON-NLS-1$
        	}
            return rootNode1;
        } else if(sourceNode2 != null && sourceNode2.hasBooleanProperty(NodeConstants.Info.MAKE_DEP)) {
        	if (analysisRecord.recordDebug()) {
        		analysisRecord.println("Making access node dependent due to hint: "+ sourceNode2.nodeToString());                     //$NON-NLS-1$
        	}
            return rootNode2;
        }
        
        return null;
    }

    /**
     * Mark the specified access node to be made dependent
     * @param sourceNode Node to make dependent
     */
    boolean markDependent(PlanNode sourceNode, PlanNode joinNode) {

        boolean isLeft = joinNode.getFirstChild() == sourceNode;
        
        // Get new access join node properties based on join criteria
        List independentExpressions = (List)(isLeft?joinNode.getProperty(NodeConstants.Info.RIGHT_EXPRESSIONS):joinNode.getProperty(NodeConstants.Info.LEFT_EXPRESSIONS)); 
        List dependentExpressions = (List)(isLeft?joinNode.getProperty(NodeConstants.Info.LEFT_EXPRESSIONS):joinNode.getProperty(NodeConstants.Info.RIGHT_EXPRESSIONS));
        
        if(independentExpressions == null || independentExpressions.isEmpty()) {
            return false;
        }

        String id = "$dsc/id" + ID.getAndIncrement(); //$NON-NLS-1$
        // Create DependentValueSource and set on the independent side as this will feed the values
        joinNode.setProperty(NodeConstants.Info.DEPENDENT_VALUE_SOURCE, id);

        List crits = getDependentCriteriaNodes(id, independentExpressions, dependentExpressions);
        
        PlanNode newRoot = sourceNode;
        
        for (Iterator i = crits.iterator(); i.hasNext();) {
            PlanNode crit = (PlanNode)i.next();
            newRoot.addAsParent(crit);
            newRoot = crit;
        }
              
        if (isLeft) {
            JoinUtil.swapJoinChildren(joinNode);
        }
        return true;
    }

    /** 
     * @param independentExpressions
     * @param dependentExpressions
     * @return
     * @since 4.3
     */
    private List getDependentCriteriaNodes(String id, List independentExpressions,
                                           List dependentExpressions) {
        
        List result = new LinkedList();
        
        Iterator depIter = dependentExpressions.iterator();
        Iterator indepIter = independentExpressions.iterator();
        
        while(depIter.hasNext()) {
            Expression depExpr = (Expression) depIter.next();
            Expression indepExpr = (Expression) indepIter.next();
            DependentSetCriteria crit = new DependentSetCriteria(SymbolMap.getExpression(depExpr), id);
            crit.setValueExpression(indepExpr);
            
            PlanNode selectNode = RelationalPlanner.createSelectNode(crit, false);
            
            selectNode.setProperty(NodeConstants.Info.IS_DEPENDENT_SET, Boolean.TRUE);
            result.add(selectNode);
        }
        
        return result;
    }
    
    public String toString() {
        return "ChooseDependent"; //$NON-NLS-1$
    }
    
}