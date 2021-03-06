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
package org.teiid.translator.salesforce.execution.visitors;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;

import org.teiid.language.ColumnReference;
import org.teiid.language.Expression;
import org.teiid.language.ExpressionValueSource;
import org.teiid.language.Insert;
import org.teiid.language.Literal;
import org.teiid.metadata.Column;
import org.teiid.metadata.RuntimeMetadata;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.salesforce.Util;


public class InsertVisitor extends CriteriaVisitor {

	@SuppressWarnings("unchecked")
	List<JAXBElement> elements = new ArrayList<JAXBElement>();
	
	private static Class<?> stringClazz = new String().getClass();
	
	public InsertVisitor(RuntimeMetadata metadata) {
		super(metadata);
	}
	
	@Override
	public void visit(Insert insert) {
		super.visit(insert);
		try {
			loadColumnMetadata(insert.getTable());
			
			List<ColumnReference> columns = insert.getColumns();
			List<Expression> values = ((ExpressionValueSource)insert.getValueSource()).getValues();
			if(columns.size() != values.size()) {
				throw new TranslatorException("Error:  columns.size and values.size are not the same.");
			}
			
			for(int i = 0; i < columns.size(); i++) {
				ColumnReference element = columns.get(i);
				Column column = element.getMetadataObject();
				Object value = values.get(i);
				String val;
				if(value instanceof Literal) {
					Literal literalValue = (Literal)value;
					val = literalValue.getValue().toString();
					if(null != val && !val.isEmpty()) {
						val = Util.stripQutes(val);
					}
				} else {
					val = value.toString();
				}
				QName qname = new QName(column.getNameInSource());
			    @SuppressWarnings( "unchecked" )
			    JAXBElement jbe = new JAXBElement( qname, stringClazz, val );
				elements.add(jbe);
			}
			
		} catch (TranslatorException ce) {
			exceptions.add(ce);
		}
	}

	@SuppressWarnings("unchecked")
	public List<JAXBElement> getMessageElements() {
		return elements;
	}

}
