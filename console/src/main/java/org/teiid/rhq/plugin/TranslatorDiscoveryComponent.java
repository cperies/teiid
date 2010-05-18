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
package org.teiid.rhq.plugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.managed.api.ComponentType;
import org.jboss.managed.api.ManagedComponent;
import org.jboss.managed.api.ManagedProperty;
import org.jboss.metatype.api.types.MetaType;
import org.jboss.metatype.api.values.CollectionValueSupport;
import org.jboss.metatype.api.values.MetaValue;
import org.jboss.metatype.api.values.MetaValueFactory;
import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.domain.configuration.PropertyList;
import org.rhq.core.domain.configuration.PropertyMap;
import org.rhq.core.domain.configuration.PropertySimple;
import org.rhq.core.pluginapi.inventory.DiscoveredResourceDetails;
import org.rhq.core.pluginapi.inventory.InvalidPluginConfigurationException;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryComponent;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryContext;
import org.teiid.adminapi.impl.PropertyMetadata;
import org.teiid.rhq.plugin.util.PluginConstants;
import org.teiid.rhq.plugin.util.ProfileServiceUtil;

/**
 * Discovery component for Teiid Translator instances
 * 
 */
public class TranslatorDiscoveryComponent implements ResourceDiscoveryComponent {

	private final Log log = LogFactory.getLog(this.getClass());

	public Set<DiscoveredResourceDetails> discoverResources(
			ResourceDiscoveryContext discoveryContext)
			throws InvalidPluginConfigurationException, Exception {
		Set<DiscoveredResourceDetails> discoveredResources = new HashSet<DiscoveredResourceDetails>();

		Set<ManagedComponent> translators = ProfileServiceUtil
				.getManagedComponents(new ComponentType(
						PluginConstants.ComponentType.Translator.TYPE,
						PluginConstants.ComponentType.Translator.SUBTYPE));

		for (ManagedComponent translator : translators) {

			Map<String, ManagedProperty> managedPropertyMap = translator
					.getProperties();
			String translatorKey = translator.getName();
			String translatorName = ProfileServiceUtil.getSimpleValue(
					translator, "name", String.class);
			/**
			 * 
			 * A discovered resource must have a unique key, that must stay the
			 * same when the resource is discovered the next time
			 */
			DiscoveredResourceDetails detail = new DiscoveredResourceDetails(
					discoveryContext.getResourceType(), // ResourceType
					translatorKey, // Resource Key
					translatorName, // Resource Name
					null, // Version
					PluginConstants.ComponentType.Translator.DESCRIPTION, // Description
					discoveryContext.getDefaultPluginConfiguration(), // Plugin
					// Config
					null // Process info from a process scan
			);

			Configuration c = detail.getPluginConfiguration();
			PropertyList list = new PropertyList("translatorList");
			PropertyMap propMap = null;
			c.put(list);

			for (ManagedProperty prop : managedPropertyMap.values()) {
				propMap = new PropertyMap("translatorMap");
				String name = prop.getName();
				if (name.equals("translator-property")) {
					getTranslatorValues(prop.getValue(), propMap, list);
				} else {
					propMap.put(new PropertySimple("name", name));
					propMap.put(new PropertySimple("value", ProfileServiceUtil
							.stringValue(prop.getValue())));
					propMap.put(new PropertySimple("description", prop
							.getDescription()));
					list.add(propMap);
				}
			}

			detail.setPluginConfiguration(c);
			// Add to return values
			discoveredResources.add(detail);
			log.info("Discovered Teiid Translator: " + translatorName);
		}

		return discoveredResources;
	}

	public static <T> void getTranslatorValues(MetaValue pValue,
			PropertyMap map, PropertyList list) {
		MetaType metaType = pValue.getMetaType();
		PropertyMetadata unwrappedvalue = null;
		if (metaType.isCollection()) {
			for (MetaValue value : ((CollectionValueSupport) pValue)
					.getElements()) {
				if (value.getMetaType().isComposite()) {
					map = new PropertyMap("translatorMap");
					unwrappedvalue = (PropertyMetadata) MetaValueFactory
							.getInstance().unwrap(value);
					map
							.put(new PropertySimple("name", unwrappedvalue
									.getName()));
					map.put(new PropertySimple("value", unwrappedvalue
							.getValue()));
					map
							.put(new PropertySimple("description",
									"Custom property"));
					list.add(map);
				} else {
					throw new IllegalStateException(pValue
							+ " is not a Composite type");
				}
			}
		}
	}

}