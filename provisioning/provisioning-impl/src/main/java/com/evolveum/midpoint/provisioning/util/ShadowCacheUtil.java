package com.evolveum.midpoint.provisioning.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.evolveum.midpoint.common.QueryUtil;
import com.evolveum.midpoint.common.result.OperationResult;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.exception.SchemaException;
import com.evolveum.midpoint.schema.holder.XPathHolder;
import com.evolveum.midpoint.schema.holder.XPathSegment;
import com.evolveum.midpoint.schema.processor.Property;
import com.evolveum.midpoint.schema.processor.ResourceObject;
import com.evolveum.midpoint.schema.processor.ResourceObjectAttribute;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_1.AccountShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.QueryType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceObjectShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceObjectShadowType.Attributes;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceType;

public class ShadowCacheUtil {
	
	public static ResourceObjectShadowType createShadow(ResourceObject resourceObject, ResourceType resource,
			ResourceObjectShadowType shadow) throws SchemaException {

		if (shadow == null) {
			// Determine correct type for the shadow
			if (resourceObject.isAccountType()) {
				shadow = new AccountShadowType();
			} else {
				shadow = new ResourceObjectShadowType();
			}
		}

		if (shadow.getObjectClass() == null) {
			shadow.setObjectClass(resourceObject.getDefinition().getTypeName());
		}
		if (shadow.getName() == null) {
			shadow.setName(determineShadowName(resourceObject));
		}
		if (shadow.getResource() == null) {
			shadow.setResourceRef(ObjectTypeUtil.createObjectRef(resource));
		}
		if (shadow.getAttributes() == null) {
			Attributes attributes = new Attributes();
			shadow.setAttributes(attributes);
		}

		Document doc = DOMUtil.getDocument();

		// Add all attributes to the shadow
		shadow.getAttributes().getAny().clear();
		for (ResourceObjectAttribute attr : resourceObject.getAttributes()) {
			try {
				List<Object> eList = attr.serializeToJaxb(doc);
				shadow.getAttributes().getAny().addAll(eList);
			} catch (SchemaException e) {
				throw new SchemaException("An error occured while serializing attribute " + attr
						+ " to DOM: " + e.getMessage(), e);
			}
		}

		return shadow;
	}
	
	private static String determineShadowName(ResourceObject resourceObject) throws SchemaException {
		if (resourceObject.getNamingAttribute() == null) {
			// No naming attribute defined. Try to fall back to identifiers.
			Set<ResourceObjectAttribute> identifiers = resourceObject.getIdentifiers();
			// We can use only single identifiers (not composite)
			if (identifiers.size() == 1) {
				Property identifier = identifiers.iterator().next();
				// Only single-valued identifiers
				Set<Object> values = identifier.getValues();
				if (values.size() == 1) {
					Object value = values.iterator().next();
					// and only strings
					if (value instanceof String) {
						return (String) value;
					}
				}
			}
			// Identifier is not usable as name
			// TODO: better identification of a problem
			throw new SchemaException("No naming attribute defined (and identifier not usable)");
		}
		// TODO: Error handling
		return resourceObject.getNamingAttribute().getValue(String.class);
	}
	
	public static ResourceObjectShadowType createRepositoryShadow(ResourceObject resourceObject,
			ResourceType resource, ResourceObjectShadowType shadow) throws SchemaException {
		
		shadow = createShadow(resourceObject, resource, shadow);
		Document doc = DOMUtil.getDocument();

		// Add all attributes to the shadow
		shadow.getAttributes().getAny().clear();
		Set<ResourceObjectAttribute> identifiers = resourceObject.getIdentifiers();
		for (Property p : identifiers) {
			try {
				List<Object> eList = p.serializeToJaxb(doc);
				shadow.getAttributes().getAny().addAll(eList);
			} catch (SchemaException e) {
				throw new SchemaException("An error occured while serializing property " + p + " to DOM: "
						+ e.getMessage(), e);
			}
		}

		if (shadow instanceof AccountShadowType) {
			((AccountShadowType) shadow).setCredentials(null);
		}

		return shadow;

	}
	
	public static QueryType createSearchShadowQuery(Set<ResourceObjectAttribute> identifiers, OperationResult parentResult) throws SchemaException{
		XPathHolder xpath = createXpathHolder();
		Document doc = DOMUtil.getDocument();
		List<Object> values = new ArrayList<Object>();
		
		for (Property identifier : identifiers) {
			values.addAll(identifier.serializeToJaxb(doc));
		}
		Element filter;
		try {
			filter = QueryUtil.createEqualFilter(doc, xpath, values);
		} catch (SchemaException e) {
			parentResult.recordFatalError(e);
			throw e;
		}

		QueryType query = new QueryType();
		query.setFilter(filter);
		return query;
	}
	
	public static QueryType createSearchShadowQuery(ResourceObject resourceObject, ResourceType resource, OperationResult parentResult) throws SchemaException{
		XPathHolder xpath = createXpathHolder();
		Property identifier = resourceObject.getIdentifier();

		Set<Object> idValues = identifier.getValues();
		// Only one value is supported for an identifier
		if (idValues.size() > 1) {
//			LOGGER.error("More than one identifier value is not supported");
			// TODO: This should probably be switched to checked exception later
			throw new IllegalArgumentException("More than one identifier value is not supported");
		}
		if (idValues.size() < 1) {
//			LOGGER.error("The identifier has no value");
			// TODO: This should probably be switched to checked exception later
			throw new IllegalArgumentException("The identifier has no value");
		}

		// We have all the data, we can construct the filter now
		Document doc = DOMUtil.getDocument();
		Element filter;
		try {
			filter = QueryUtil.createAndFilter(
					doc,
					QueryUtil.createEqualRefFilter(doc, null, SchemaConstants.I_RESOURCE_REF,
							resource.getOid()),
					QueryUtil.createEqualFilter(doc, xpath, identifier.serializeToJaxb(doc)));
		} catch (SchemaException e) {
//			LOGGER.error("Schema error while creating search filter: {}", e.getMessage(), e);
			throw new SchemaException("Schema error while creating search filter: " + e.getMessage(), e);
		}

		QueryType query = new QueryType();
		query.setFilter(filter);

//		LOGGER.trace("created query " + DOMUtil.printDom(filter));

		return query;
	}
	
	private static XPathHolder createXpathHolder(){
		XPathSegment xpathSegment = new XPathSegment(SchemaConstants.I_ATTRIBUTES);	
		List<XPathSegment> xpathSegments = new ArrayList<XPathSegment>();
		xpathSegments.add(xpathSegment);
		XPathHolder xpath = new XPathHolder(xpathSegments);
		return xpath;
	}

}
