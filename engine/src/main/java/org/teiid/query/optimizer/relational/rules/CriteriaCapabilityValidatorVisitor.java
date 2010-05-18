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

import java.util.Arrays;
import java.util.Collection;

import org.teiid.api.exception.query.QueryMetadataException;
import org.teiid.core.TeiidComponentException;
import org.teiid.query.execution.QueryExecPlugin;
import org.teiid.query.function.metadata.FunctionMethod;
import org.teiid.query.metadata.QueryMetadataInterface;
import org.teiid.query.metadata.SupportConstants;
import org.teiid.query.optimizer.capabilities.CapabilitiesFinder;
import org.teiid.query.optimizer.capabilities.SourceCapabilities;
import org.teiid.query.optimizer.capabilities.SourceCapabilities.Capability;
import org.teiid.query.processor.ProcessorPlan;
import org.teiid.query.processor.relational.AccessNode;
import org.teiid.query.processor.relational.RelationalNode;
import org.teiid.query.processor.relational.RelationalPlan;
import org.teiid.query.sql.LanguageObject;
import org.teiid.query.sql.LanguageVisitor;
import org.teiid.query.sql.lang.AbstractCompareCriteria;
import org.teiid.query.sql.lang.AbstractSetCriteria;
import org.teiid.query.sql.lang.Command;
import org.teiid.query.sql.lang.CompareCriteria;
import org.teiid.query.sql.lang.CompoundCriteria;
import org.teiid.query.sql.lang.DependentSetCriteria;
import org.teiid.query.sql.lang.ExistsCriteria;
import org.teiid.query.sql.lang.IsNullCriteria;
import org.teiid.query.sql.lang.MatchCriteria;
import org.teiid.query.sql.lang.NotCriteria;
import org.teiid.query.sql.lang.Query;
import org.teiid.query.sql.lang.SetCriteria;
import org.teiid.query.sql.lang.SubqueryCompareCriteria;
import org.teiid.query.sql.lang.SubqueryContainer;
import org.teiid.query.sql.lang.SubquerySetCriteria;
import org.teiid.query.sql.navigator.PreOrderNavigator;
import org.teiid.query.sql.symbol.AggregateSymbol;
import org.teiid.query.sql.symbol.CaseExpression;
import org.teiid.query.sql.symbol.Function;
import org.teiid.query.sql.symbol.GroupSymbol;
import org.teiid.query.sql.symbol.ScalarSubquery;
import org.teiid.query.sql.symbol.SearchedCaseExpression;
import org.teiid.query.sql.symbol.XMLAttributes;
import org.teiid.query.sql.symbol.XMLElement;
import org.teiid.query.sql.symbol.XMLForest;
import org.teiid.query.sql.symbol.XMLNamespaces;
import org.teiid.query.sql.util.SymbolMap;
import org.teiid.query.sql.visitor.EvaluatableVisitor;
import org.teiid.query.sql.visitor.GroupCollectorVisitor;


/**
 */
public class CriteriaCapabilityValidatorVisitor extends LanguageVisitor {

    // Initialization state
    private Object modelID;
    private QueryMetadataInterface metadata;
    private CapabilitiesFinder capFinder;

    // Retrieved during initialization and cached
    private SourceCapabilities caps;
    
    // Output state
    private TeiidComponentException exception;
    private boolean valid = true;

    /**
     * @param iterator
     * @throws TeiidComponentException 
     * @throws QueryMetadataException 
     */
    CriteriaCapabilityValidatorVisitor(Object modelID, QueryMetadataInterface metadata, CapabilitiesFinder capFinder, SourceCapabilities caps) throws QueryMetadataException, TeiidComponentException {        
        this.modelID = modelID;
        this.metadata = metadata;
        this.capFinder = capFinder;
        this.caps = caps;
    }
    
    @Override
    public void visit(XMLAttributes obj) {
    	markInvalid();
    }
    
    @Override
    public void visit(XMLNamespaces obj) {
    	markInvalid();
    }
    
    @Override
    public void visit(XMLForest obj) {
    	markInvalid();
    }
    
    @Override
    public void visit(XMLElement obj) {
    	markInvalid();
    }
    
    public void visit(AggregateSymbol obj) {
        try {
            if(! CapabilitiesUtil.supportsAggregateFunction(modelID, obj, metadata, capFinder)) {
                markInvalid();
            }         
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }
    
    public void visit(CaseExpression obj) {
        if(! this.caps.supportsCapability(Capability.QUERY_CASE)) {
            markInvalid();
        }
    }
    
    public void visit(CompareCriteria obj) {
    	checkCompareCriteria(obj);
    }
    
    public void checkCompareCriteria(AbstractCompareCriteria obj) {
        boolean negated = false;
        // Check if operation is allowed
        Capability operatorCap = null;
        switch(obj.getOperator()) {
            case CompareCriteria.NE: 
                negated = true;
            case CompareCriteria.EQ: 
                operatorCap = Capability.CRITERIA_COMPARE_EQ;
                break; 
            case CompareCriteria.LT: 
            case CompareCriteria.GT: 
                negated = true;
            case CompareCriteria.LE: 
            case CompareCriteria.GE: 
                operatorCap = Capability.CRITERIA_COMPARE_ORDERED;
                break;                        
        }

        // Check if compares are allowed
        if(! this.caps.supportsCapability(operatorCap)) {
            markInvalid();
        }                       
        if (negated && !this.caps.supportsCapability(Capability.CRITERIA_NOT)) {
        	markInvalid();
        }
        
        // Check capabilities of the elements
        try {
            checkElementsAreSearchable(obj, SupportConstants.Element.SEARCHABLE_COMPARE);                                
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }

    public void visit(CompoundCriteria crit) {
        int operator = crit.getOperator();
        
        // Verify capabilities are supported
        if(operator == CompoundCriteria.OR) {
            // Check if OR is allowed
            if(! this.caps.supportsCapability(Capability.CRITERIA_OR)) {
                markInvalid();
                return;
            }                       
        }
    }

    public void visit(Function obj) {
        try {
            //if the function can be evaluated then return as it will get replaced during the final rewrite 
            if (EvaluatableVisitor.willBecomeConstant(obj, true)) { 
                return; 
            }
            if(obj.getFunctionDescriptor().getPushdown() == FunctionMethod.CANNOT_PUSHDOWN || ! CapabilitiesUtil.supportsScalarFunction(modelID, obj, metadata, capFinder)) {
                markInvalid();
            }
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }

    public void visit(IsNullCriteria obj) {
        // Check if compares are allowed
        if(! this.caps.supportsCapability(Capability.CRITERIA_ISNULL)) {
            markInvalid();
            return;
        }
        
        if (obj.isNegated() && !this.caps.supportsCapability(Capability.CRITERIA_NOT)) {
        	markInvalid();
            return;
        }        
    }

    public void visit(MatchCriteria obj) {
        // Check if compares are allowed
        if(! this.caps.supportsCapability(Capability.CRITERIA_LIKE)) {
            markInvalid();
            return;
        }
        
        // Check ESCAPE char if necessary
        if(obj.getEscapeChar() != MatchCriteria.NULL_ESCAPE_CHAR) {
            if(! this.caps.supportsCapability(Capability.CRITERIA_LIKE_ESCAPE)) {
                markInvalid();
                return;
            }                
        }
        
        //check NOT
        if(obj.isNegated() && ! this.caps.supportsCapability(Capability.CRITERIA_NOT)) {
        	markInvalid();
        	return;
        }

        // Check capabilities of the elements
        try {
            checkElementsAreSearchable(obj, SupportConstants.Element.SEARCHABLE_LIKE);
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }

    public void visit(NotCriteria obj) {
        // Check if compares are allowed
        if(! this.caps.supportsCapability(Capability.CRITERIA_NOT)) {
            markInvalid();
            return;
        }
    }

    public void visit(SearchedCaseExpression obj) {
        if (this.caps == null) {
            return;
        }
         
        if(! this.caps.supportsCapability(Capability.QUERY_SEARCHED_CASE)) {
            markInvalid();
        }
    }
    
    public void visit(SetCriteria crit) {
    	checkAbstractSetCriteria(crit);
        try {    
            int maxSize = CapabilitiesUtil.getMaxInCriteriaSize(modelID, metadata, capFinder); 
            
            if (maxSize > 0 && crit.getValues().size() > maxSize) {
                markInvalid();
                return;
            }
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }

    /**
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.ExistsCriteria)
     */
    public void visit(ExistsCriteria crit) {
        // Check if exists criteria are allowed
        if(! this.caps.supportsCapability(Capability.CRITERIA_EXISTS)) {
            markInvalid();
            return;
        }
        
        try {
			if (validateSubqueryPushdown(crit, modelID, metadata, capFinder) == null) {
				markInvalid();
			}
		} catch (TeiidComponentException e) {
			handleException(e);
		}
    }

    /**
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.SubqueryCompareCriteria)
     */
    public void visit(SubqueryCompareCriteria crit) {
        // Check if quantification operator is allowed
        Capability capability = Capability.QUERY_SUBQUERIES_SCALAR;
        switch(crit.getPredicateQuantifier()) {
            case SubqueryCompareCriteria.ALL:
                capability = Capability.CRITERIA_QUANTIFIED_ALL;
                break;
            case SubqueryCompareCriteria.ANY:
                capability = Capability.CRITERIA_QUANTIFIED_SOME;
                break;
            case SubqueryCompareCriteria.SOME:
                capability = Capability.CRITERIA_QUANTIFIED_SOME;
                break;
        }
        if(! this.caps.supportsCapability(capability)) {
            markInvalid();
            return;
        }
        
        checkCompareCriteria(crit);
        
        // Check capabilities of the elements
        try {
            if (validateSubqueryPushdown(crit, modelID, metadata, capFinder) == null) {
            	markInvalid();
            }
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }
    
    @Override
    public void visit(ScalarSubquery obj) {
    	try {    
            // Check if compares are allowed
            if(! this.caps.supportsCapability(Capability.QUERY_SUBQUERIES_SCALAR)) {
                markInvalid();
                return;
            }
            if (validateSubqueryPushdown(obj, modelID, metadata, capFinder) == null) {
            	markInvalid();
            }
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }

    public void visit(SubquerySetCriteria crit) {
    	checkAbstractSetCriteria(crit);
        try {    
            // Check if compares with subqueries are allowed
            if(! this.caps.supportsCapability(Capability.CRITERIA_IN_SUBQUERY)) {
                markInvalid();
                return;
            }

            if (validateSubqueryPushdown(crit, modelID, metadata, capFinder) == null) {
            	markInvalid();
            }
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }
    }
    
    public void checkAbstractSetCriteria(AbstractSetCriteria crit) {
        try {    
            // Check if compares are allowed
            if(! this.caps.supportsCapability(Capability.CRITERIA_IN)) {
                markInvalid();
                return;
            }
            
            if (crit.isNegated() && !this.caps.supportsCapability(Capability.CRITERIA_NOT)) {
            	markInvalid();
                return;
            }
            // Check capabilities of the elements
            checkElementsAreSearchable(crit, SupportConstants.Element.SEARCHABLE_COMPARE);                        
                 
        } catch(QueryMetadataException e) {
            handleException(new TeiidComponentException(e));
        } catch(TeiidComponentException e) {
            handleException(e);            
        }

    }

    public void visit(DependentSetCriteria crit) {
    	checkAbstractSetCriteria(crit);
    }

    private void checkElementsAreSearchable(LanguageObject crit, int searchableType)
    throws QueryMetadataException, TeiidComponentException {
    	if (!CapabilitiesUtil.checkElementsAreSearchable(Arrays.asList(crit), metadata, searchableType)) {
    		markInvalid();
    	}
    }
    
    /**
     * Return null if the subquery cannot be pushed down, otherwise the model
     * id of the pushdown target.
     * @param subqueryContainer
     * @param critNodeModelID
     * @param metadata
     * @param capFinder
     * @return
     * @throws TeiidComponentException
     */
    static Object validateSubqueryPushdown(SubqueryContainer subqueryContainer, Object critNodeModelID, QueryMetadataInterface metadata, CapabilitiesFinder capFinder) throws TeiidComponentException {
    	ProcessorPlan plan = subqueryContainer.getCommand().getProcessorPlan();
    	if (plan != null) {
	        if(!(plan instanceof RelationalPlan)) {
	            return null;
	        }
	                    
	        RelationalPlan rplan = (RelationalPlan) plan;
	        
	        // Check that the plan is just an access node                
	        RelationalNode accessNode = rplan.getRootNode();
	        if(accessNode == null || ! (accessNode instanceof AccessNode) || accessNode.getChildren()[0] != null) {
	            return null;
	        }
	        
	        // Check that command in access node is a query
	        Command command = ((AccessNode)accessNode).getCommand();
	        if(command == null || !(command instanceof Query) || ((Query)command).getIsXML()) {
	            return null;
	        }
	        
	        // Check that query in access node is for the same model as current node
	        try {                
	            Collection subQueryGroups = GroupCollectorVisitor.getGroupsIgnoreInlineViews(command, false);
	            if(subQueryGroups.size() == 0) {
	                // No FROM?
	                return null;
	            }
	            GroupSymbol subQueryGroup = (GroupSymbol)subQueryGroups.iterator().next();
	
	            Object modelID = metadata.getModelID(subQueryGroup.getMetadataID());
	            if (critNodeModelID == null) {
	            	critNodeModelID = modelID;
	            } else if(!CapabilitiesUtil.isSameConnector(critNodeModelID, modelID, metadata, capFinder)) {
	                return null;
	            }
	        } catch(QueryMetadataException e) {
	            throw new TeiidComponentException(e, QueryExecPlugin.Util.getString("RulePushSelectCriteria.Error_getting_modelID")); //$NON-NLS-1$
	        }  
    	}
    	if (critNodeModelID == null) {
    		return null;
    	}
        // Check whether source supports correlated subqueries and if not, whether criteria has them
        SymbolMap refs = subqueryContainer.getCommand().getCorrelatedReferences();
        try {
            if(refs != null && !refs.asMap().isEmpty()) {
                if(! CapabilitiesUtil.supports(Capability.QUERY_SUBQUERIES_CORRELATED, critNodeModelID, metadata, capFinder)) {
                    return null;
                }
                //TODO: this check sees as correlated references as coming from the containing scope
                //but this is only an issue with deeply nested subqueries
                if (!CriteriaCapabilityValidatorVisitor.canPushLanguageObject(subqueryContainer.getCommand(), critNodeModelID, metadata, capFinder)) {
                    return null;
                }
            }
        } catch(QueryMetadataException e) {
            throw new TeiidComponentException(e);                  
        }

        // Found no reason why this node is not eligible
        return critNodeModelID;
    }
        
    private void handleException(TeiidComponentException e) {
        this.valid = false;
        this.exception = e;
        setAbort(true);
    }
    
    public TeiidComponentException getException() {
        return this.exception;
    }
    
    private void markInvalid() {
        this.valid = false;
        setAbort(true);
    }
    
    public boolean isValid() {
        return this.valid;
    }

    public static boolean canPushLanguageObject(LanguageObject obj, Object modelID, QueryMetadataInterface metadata, CapabilitiesFinder capFinder) throws QueryMetadataException, TeiidComponentException {
        if(obj == null) {
            return true;
        }
        
        if(modelID == null || metadata.isVirtualModel(modelID)) {
            // Couldn't determine model ID, so give up
            return false;
        } 
        
        String modelName = metadata.getFullName(modelID);
        SourceCapabilities caps = capFinder.findCapabilities(modelName);

        if (caps == null) {
        	return true; //this doesn't seem right, but tests were expecting it...
        }
        
        CriteriaCapabilityValidatorVisitor visitor = new CriteriaCapabilityValidatorVisitor(modelID, metadata, capFinder, caps);
        PreOrderNavigator.doVisit(obj, visitor);
        
        if(visitor.getException() != null) {
            throw visitor.getException();
        } 

        return visitor.isValid();
    }

}